package com.classmemory.app.review

import com.classmemory.app.course.CourseContext
import com.classmemory.app.course.CourseContextStore
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Purely synthetic UI acceptance data. Lives in test source, never in an APK or a model request. */
class VisualAcceptanceFixtureTest {
    @Test fun createsValidatedSyntheticReviewAndRoomSeed() = runBlocking {
        val configured = System.getenv("CLASSMEMORY_VISUAL_FIXTURE_DIR")?.takeIf { it.isNotBlank() }
        val root = configured?.let(::File) ?: Files.createTempDirectory("classmemory-visual-fixture-").toFile()
        try {
            check(root.isDirectory || root.mkdirs())
            val files = File(root, "files").apply { mkdirs() }
            val session = "visual-acceptance-20261106"
            val startedAt = Instant.parse("2026-11-06T01:00:00Z").toEpochMilli()
            val title = "视觉验收样例 · 二叉树与遍历"
            val courseFile = File(files, "course-context.json")
            check(!courseFile.exists()) { "Choose a fresh fixture directory; do not overwrite existing evidence" }
            val contexts = CourseContextStore(courseFile)
            val course = contexts.save(CourseContext(
                id = "visual-course-data-structures", name = "数据结构 · 视觉验收样例",
                background = "纯合成课堂，仅用于界面验收；不是教师录音、真实模型输出或识别准确率证据。",
                goals = "理解二叉树结构、前序遍历、递归与显式栈之间的关系。",
                chapters = "树的定义\n递归遍历\n非递归遍历\n复杂度与边界条件",
                terminology = "根节点；左子树；右子树；递归；栈；时间复杂度",
                prerequisites = "顺序结构、函数调用和栈的后进先出性质。",
                sources = "自编视觉验收教材片段，无外部课堂资料。",
            ))
            contexts.assign(session, course.id)
            val titles = listOf("二叉树的结构与访问顺序", "递归前序遍历", "用栈实现非递归遍历", "复杂度与边界条件")
            val transcriptTexts = listOf(
                "视觉验收样例：今天用自编二叉树讨论遍历。树有根节点 A，左子树根节点 B，右子树根节点 C。",
                "每个节点最多有两棵子树。前序遍历先访问根，再访问左子树，最后访问右子树。",
                "这里的左右是树结构中的位置关系。交换左右子树以后，前序访问顺序也可能改变。",
                "递归函数先判断当前节点是否为空；空节点不继续处理。然后访问当前节点。",
                "访问根以后调用左子树的递归，再调用右子树的递归。调用栈会保存返回位置。",
                "如果没有终止条件，递归就不能按预期结束。边界判断是算法的一部分。",
                "非递归版本使用显式栈。先把根入栈，每次取出栈顶并访问它。",
                "为了先访问左子树，要先将右子节点入栈，再将左子节点入栈。栈是后进先出。",
                "不要把空指针也当成需要访问的节点。只在对应子节点非空时入栈。",
                "每个节点只入栈和出栈一次，访问所有 n 个节点的时间复杂度是 O(n)。",
                "辅助栈与树的形状有关。递归深度与高度有关，退化成链时可能达到 n。",
                "视觉验收样例结束：请用同一棵小树手工验证访问顺序，再解释正确性和边界条件。",
            )
            val rows = transcriptTexts.mapIndexed { i, text -> JSONObject()
                .put("id", "visual-segment-${i.toString().padStart(2, '0')}").put("sessionId", session)
                .put("startMs", i * 30_000L).put("endMs", (i + 1) * 30_000L).put("text", text)
                .put("createdAt", startedAt + (i + 1) * 30_000L).put("dedupeKey", "visual-fixture-$i") }
            val photoRows = listOf(75_000L, 205_000L).mapIndexed { index, at ->
                val id = "visual-board-$index"
                val relative = "board-photos/$session/$id.png"
                val png = File(files, relative).apply { parentFile!!.mkdirs() }
                png.writeBytes(syntheticBoardPng(index))
                JSONObject().put("id", id).put("sessionId", session).put("capturedAtMs", at)
                    .put("relativePath", relative).put("createdAt", startedAt + at)
            }
            val noteRows = listOf(
                Triple("visual-note-question", "QUESTION", "为什么非递归前序遍历必须先压右子树，再压左子树？请展开讲解。"),
                Triple("visual-note-comment", "COMMENT", "视觉验收样例：把栈的变化和节点访问顺序分开写，不能只背结论。"),
            ).mapIndexed { index, value -> JSONObject().put("id", value.first).put("sessionId", session)
                .put("atMs", 216_000L + index * 1_000L).put("kind", value.second).put("text", value.third)
                .put("createdAt", startedAt + 216_000L + index * 1_000L) }
            // This is the same current-session ordering and identity transformation as prepareReview.
            val sources = rows.map { ReviewSource("s_${session}_${it.getString("id")}", session, it.getString("id"), it.getLong("startMs"), it.getLong("endMs"), it.getString("text"), true) }
            val photos = photoRows.map { ReviewPhotoRef(it.getString("id"), session, it.getLong("capturedAtMs")) }
            val notes = noteRows.map { ReviewNote(it.getString("id"), it.getLong("atMs"), it.getString("kind"), it.getString("text")) }
            // ReviewJson intentionally omits CourseContext.updatedAt; revision/content define identity.
            val raw = ReviewInput(session, "", "zh", title, "2026-11-06", contexts.snapshot().courseFor(session)?.copy(updatedAt = 0), sources, photos, notes, analysisVersion = 3)
            val input = raw.copy(sourceHash = raw.contentHash())
            ReviewContracts.validate(input)
            assertEquals(input.sourceHash, input.copy(today = "2026-11-07").contentHash())
            assertEquals(input.sourceHash, input.copy(course = contexts.snapshot().courseFor(session)).contentHash())
            val sections = titles.indices.map { i -> ReviewSection("visual-section-$i", titles[i], sources.slice(i * 3 until i * 3 + 3).map { it.id }) }
            val topics = titles.indices.map { i -> ReviewTopic("visual-unit-$i", titles[i], listOf(sections[i].id), sections[i].sourceIds) }
            val outline = ReviewOutline("视觉验收样例：前半部分解释二叉树和递归访问顺序，后半部分用显式栈改写遍历，再检查复杂度与边界条件。", sections, topics)
            val units = topics.mapIndexed { i, topic -> ReviewUnit(topic.id, topic.title,
                "视觉验收样例：${topic.title}，从课堂给定的小树逐步推导。", "本节采用二叉树前序遍历。",
                "说明每一步的理由，并区分访问节点与入栈操作。", "自编小树 A(B(D,E),C)", topic.sourceIds,
                if (i == 0) listOf(photos[0].id) else if (i == 2) listOf(photos[1].id) else emptyList(), topic.sectionIds, longMarkdown(i)) }
            val plan = ReviewPlan("一步一步，学明白", "视觉验收样例：按任务自查；勾选是用户复习进度，并非系统判断已掌握。", "2026-11-06", units.mapIndexed { index, unit ->
                ReviewStep("第 ${index + 1} 轮", "复习${unit.title}", listOf(unit.id),
                    listOf("说清二叉树的基本结构", "写出递归调用和返回过程", "解释为什么先压右再压左", "比较时间与空间复杂度")[index],
                    listOf("不看资料，说清根、左右子树和节点关系。", "能写出空节点终止条件，并说明根左右顺序。", "能逐步画出栈的变化，给出相同的前序序列。", "能区分平衡树和退化链，并解释 O(n) 的来源。")[index]) })
            val graded = ReviewExerciseAttempt("visual-attempt-1", "我选 B，因为左边要先出来。右边先放进去。其他选项我还说不清。", "graded", "partial",
                "视觉验收样例反馈：选择与主要理由正确，但还需要解释其他选项为何不能保证根左右顺序。",
                "先入后出决定了右子节点要先入栈。A 会先访问右侧，C 会遗漏分支，D 改变了数据结构。", listOf(sources[7].id))
            val exercises = listOf(
                ReviewExercise("visual-exercise-1", units[2].id, "MCQ", "视觉验收样例：非递归前序遍历应以什么顺序压入子节点？请说明选择理由，并逐项说明其他选项为什么不合适。", listOf("A. 左子节点先入栈", "B. 右子节点先入栈", "C. 只将非空左子节点入栈", "D. 使用队列替代栈"), attempts = listOf(graded)),
                ReviewExercise("visual-exercise-2", units[0].id, "SHORT", "视觉验收样例：请用自己的话说明二叉树中根节点、左子树和右子树的关系，并给出一个三节点例子。", emptyList()),
                ReviewExercise("visual-exercise-3", units[3].id, "MCQ", "视觉验收样例：每个节点访问一次的前序遍历，时间复杂度是什么？写出选项、依据和排除其他选项的理由。", listOf("A. O(1)", "B. O(log n)", "C. O(n)", "D. O(n²)")),
                ReviewExercise("visual-exercise-4", units[1].id, "SHORT", "视觉验收样例：递归函数为什么必须先判断空节点？如果省略判断可能发生什么？", emptyList()),
                ReviewExercise("visual-exercise-followup", units[2].id, "SHORT", "视觉验收样例 · 针对练习：根节点 A 的左右子节点分别为 B、C。先把 B 再把 C 入栈，下一步访问谁？请指出错误并改正。", emptyList(), parentId = "visual-exercise-1"),
            )
            validateNewExerciseMix(exercises.filter { it.parentId == null }.map { ReviewExerciseDraft(it.unitId, it.kind, it.question, it.options) }, units.size)
            val qa = listOf(
                ReviewQaThread("visual-qa-note", units[2].id, listOf(ReviewQaTurn(notes[0].text,
                    "**视觉验收样例答复。** 栈后进先出。要让左子树先被取出，就要让它后入栈。\n\n1. 先压右子节点。\n2. 再压左子节点。\n3. 弹出的第一个子节点就是左侧。", sourceIds = listOf(sources[7].id), id = "visual-turn-note")), noteId = notes[0].id, relatedNoteIds = listOf(notes[1].id)),
                ReviewQaThread("visual-qa-global", null, listOf(ReviewQaTurn("视觉验收样例：这节课最应该串起来理解的是什么？", "把 **结构 → 访问顺序 → 递归调用 → 显式栈 → 复杂度** 连起来。先手工验证同一棵树，再比较两种程序。", sourceIds = listOf(sources[0].id, sources[7].id), id = "visual-turn-global"))),
                ReviewQaThread("exercise:${graded.id}", units[2].id, listOf(ReviewQaTurn(exercises[0].question + "\n\n学生回答：" + graded.answer, "部分正确\n\n${graded.feedback}\n\n${graded.reasoning}", sourceIds = graded.sourceIds, id = "exercise:${graded.id}"))),
            )
            val run = ReviewRun(input, outline, units.associateBy { it.id }, plan, updatedAt = startedAt + 360_000L,
                completedStepIndices = setOf(0), qaThreads = qa, notesProcessed = true,
                graph = ReviewGraph(units.map { it.id }, listOf(ReviewGraphEdge(units[0].id, units[1].id, "derives"), ReviewGraphEdge(units[1].id, units[2].id, "parallel"), ReviewGraphEdge(units[2].id, units[3].id, "related"))),
                exercises = exercises, exerciseNotices = listOf(ReviewExerciseNotice("visual-notice-followup", "visual-exercise-followup")))
            ReviewContracts.validate(run)
            assertEquals(run, ReviewJson.run(ReviewJson.run(run)))
            val store = ReviewStore(File(files, "class-reviews"))
            store.save(run)
            assertEquals(run, store.load(session, input.sourceHash))
            store.saveExerciseDraft(session, input.sourceHash, "visual-exercise-2", "视觉验收样例草稿：根节点是起点，左右子树本身也可以继续分成更小的树。")
            assertTrue(store.loadExerciseDraft(session, input.sourceHash, "visual-exercise-2")!!.contains("视觉验收样例草稿"))
            val sessions = JSONObject().put("id", session).put("startedAt", startedAt).put("title", title).put("courseType", course.name)
                .put("endedAt", startedAt + 360_000L).put("status", "completed").put("durationSec", 360)
                .put("finalCharCount", transcriptTexts.sumOf { it.length }).put("lastHeartbeatAt", startedAt + 360_000L).put("isOrganized", 0)
            val seed = JSONObject().put("purpose", "视觉验收样例 / Synthetic visual acceptance fixture. Not model quality evidence.")
                .put("roomVersion", 6).put("sessionId", session).put("sourceHash", input.sourceHash)
                .put("tables", JSONObject().put("sessions", JSONArray().put(sessions)).put("transcript_segments", JSONArray(rows))
                    .put("board_photos", JSONArray(photoRows)).put("classroom_notes", JSONArray(noteRows))
                    .put("course_types", JSONArray().put(JSONObject().put("name", course.name).put("createdAt", startedAt))))
            File(root, "room-seed.json").writeText(seed.toString(2))
            File(root, "review-input.json").writeText(ReviewJson.input(input).toString(2))
            File(root, "README.txt").writeText("视觉验收样例。仅限隔离模拟器/测试数据副本。无 API 请求、无真实课堂、无内置密钥。files/ 是应用私有文件映射，room-seed.json 是 Room v6 现有数据库的合成行。必须同时注入课程上下文、课堂行与 ReviewStore 文件，保持 sourceHash 一致。不要加入交付 APK，不要覆盖真实用户资料。")
            assertEquals(4, run.units.size)
            assertEquals(4, run.exercises.count { it.parentId == null })
            assertEquals(1, run.exercises.count { it.parentId != null })
            assertEquals(2, photoRows.size)
            assertEquals(12, sources.size)
        } finally { if (configured == null) root.deleteRecursively() }
    }

    private fun longMarkdown(index: Int): String = """
        > **视觉验收样例**：以下文字、问答与练习反馈均为自编，用于检查排版和交互，不代表实际模型输出。

        ## ${listOf("先说清结构，再说访问", "把递归调用拆成三个动作", "后进先出决定入栈顺序", "别混淆时间与辅助空间")[index]}

        二叉树的每个节点最多具有两棵子树。**前序遍历遵循“根、左、右”**，其中访问根是当前动作，而访问左右子树是对同一个规则的再次应用。

        1. 检查当前节点是否为空。
        2. 访问当前节点，记录其数据。
        3. 依次处理左子树与右子树。

        ### 用一个小例子确认顺序

        对自编树 `A(B(D,E),C)`，前序序列为 `A B D E C`。不要把节点在纸面上的位置和栈中排列顺序混在一起。每次只解释当前动作，再观察下一步的可用节点。

        | 处理方式 | 核心状态 | 需要注意 |
        | --- | --- | --- |
        | 递归 | 函数调用栈 | 空节点立即返回 |
        | 显式栈 | 尚未访问的节点 | 右侧先入，左侧后入 |
        | 对照检查 | 同一棵自编小树 | 两种实现应给出相同序列 |

        ### 数学表达与复杂度

        每个节点只做常数次操作，所以有

        \[ T(n)=T(n_L)+T(n_R)+O(1)=O(n). \]

        平衡树的高度通常随 \(\log n\) 增长；退化链的高度可能达到 \(n\)。这里描述的是结构条件，不应把平均情况当成所有输入的保证。

        ```kotlin
        fun preorder(node: Node?) {
            if (node == null) return
            visit(node)
            preorder(node.left)
            preorder(node.right)
        }
        ```

        ### 课堂疑问展开

        **为什么先压右边？** 因为栈后进先出。若希望左子节点先弹出，就必须在右子节点之后把左子节点压进去。先后顺序服务于访问目标，而不是一个需要机械背诵的固定口令。

        - 若改成中序或后序，应重新分析状态和返回时机。
        - 若子节点为空，不把它当作一个可访问节点。
        - 不要在还没检查栈是否为空时取栈顶。

        ### 自查与迁移

        请画出只有根节点的树、只有左链的树、左右都有分支的树。逐步记录每次访问的节点和剩余栈内容。如果两个实现的访问序列不同，应检查入栈顺序与空节点判断，而不是直接改写最终答案。

        这段较长内容专门用于验收滚动、公式、表格、代码换行、深色模式和大字号。继续滚动以后，QA 与出处入口仍应可以使用，折叠问题时不应删除草稿或改变课堂数据。
    """.trimIndent()

    /** Small self-authored bitmap diagram; no system font, imaging dependency or external file. */
    private fun syntheticBoardPng(variant: Int): ByteArray {
        val width = 960; val height = 600
        val pixels = IntArray(width * height) { 0xFCFBFF }
        fun rect(x: Int, y: Int, w: Int, h: Int, color: Int) { for (yy in y.coerceAtLeast(0) until (y+h).coerceAtMost(height)) for (xx in x.coerceAtLeast(0) until (x+w).coerceAtMost(width)) pixels[yy * width + xx] = color }
        val purple = 0x5141DA; val green = 0x118C79
        val glyphs = mapOf('A' to "01110100011000111111100011000110001", 'B' to "11110100011000111110100011000111110", 'C' to "01111100001000010000100001000001111", 'D' to "11110100011000110001100011000111110", 'E' to "11111100001000011110100001000011111", 'F' to "11111100001000011110100001000010000", 'G' to "01111100001000010111100011000101111", 'H' to "10001100011000111111100011000110001", 'I' to "11111001000010000100001000010011111", 'L' to "10000100001000010000100001000011111", 'M' to "10001110111010110101100011000110001", 'O' to "01110100011000110001100011000101110", 'P' to "11110100011000111110100001000010000", 'Q' to "01110100011000110001101011001001101", 'R' to "11110100011000111110101001001010001", 'S' to "01111100001000001110000010000111110", 'T' to "11111001000010000100001000010000100", 'U' to "10001100011000110001100011000101110", 'V' to "10001100011000110001100010101000100", 'W' to "10001100011000110101101011101110001", 'Y' to "10001100010101000100001000010000100")
        fun text(value: String, x: Int, y: Int, scale: Int, color: Int) { value.forEachIndexed { i, c -> glyphs[c]?.forEachIndexed { at, bit -> if (bit == '1') rect(x + i * 6 * scale + at % 5 * scale, y + at / 5 * scale, scale, scale, color) } } }
        fun line(x1: Int, y1: Int, x2: Int, y2: Int) { val n = maxOf(kotlin.math.abs(x2-x1), kotlin.math.abs(y2-y1)); for (i in 0..n) rect(x1 + (x2-x1)*i/n - 2, y1 + (y2-y1)*i/n - 2, 5, 5, purple) }
        fun node(label: String, x: Int, y: Int) { for (yy in -35..35) for (xx in -35..35) { val r = xx*xx+yy*yy; if (r in 29*29..35*35) rect(x+xx,y+yy,1,1,purple) }; text(label,x-10,y-14,4,purple) }
        rect(24,24,912,552,0xEEEAFE); rect(30,30,900,540,0xFFFFFF)
        text("VISUAL QA SAMPLE", 95, 65, 6, purple)
        text(if (variant == 0) "PREORDER" else "STACK ORDER", 90, 148, 4, green)
        line(480,220,310,340); line(480,220,650,340); line(310,340,220,455); line(310,340,400,455)
        node("A",480,220); node("B",310,340); node("C",650,340); node("D",220,455); node("E",400,455)
        text("ROOT LEFT RIGHT", 105, 520, 3, green)
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) { raw.write(0); for (x in 0 until width) { val c=pixels[y*width+x]; raw.write(c shr 16 and 255); raw.write(c shr 8 and 255); raw.write(c and 255) } }
        val compressed=ByteArrayOutputStream(); DeflaterOutputStream(compressed).use { it.write(raw.toByteArray()) }
        val png=ByteArrayOutputStream(); val out=DataOutputStream(png)
        out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type: String, data: ByteArray) { val name=type.toByteArray(Charsets.US_ASCII); out.writeInt(data.size); out.write(name); out.write(data); val crc=CRC32(); crc.update(name); crc.update(data); out.writeInt(crc.value.toInt()) }
        val header=ByteArrayOutputStream(); DataOutputStream(header).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8,2,0,0,0)) }
        chunk("IHDR", header.toByteArray()); chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf()); out.flush()
        return png.toByteArray()
    }
}

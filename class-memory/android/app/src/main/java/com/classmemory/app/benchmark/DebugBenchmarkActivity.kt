package com.classmemory.app.benchmark

import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import com.classmemory.app.ui.NotebookIllustration
import com.classmemory.app.ui.icons.ClassMemoryIcons
import com.classmemory.app.ui.theme.ClassMemoryColors
import com.classmemory.app.i18n.AppLanguage
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.classmemory.app.BuildConfig
import com.classmemory.app.MainActivity
import com.classmemory.app.service.RecordingService
import com.classmemory.app.asr.AsrHotwordPreferences
import com.classmemory.app.i18n.tr
import com.classmemory.app.ui.theme.ClassMemoryTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * User-selected recording import. Only Debug builds additionally accept automation intents
 * and expose realtime stress-test controls.
 */
class DebugBenchmarkActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN = "com.classmemory.app.benchmark.OPEN"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguage.initialize(this)
        setContent {
            ClassMemoryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    BenchmarkScreen()
                }
            }
        }
        handleAutomationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutomationIntent(intent)
    }

    private fun handleAutomationIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent?.action != ACTION_OPEN) return
        val source = intent.data ?: intent.getStringExtra(DebugBenchmarkService.EXTRA_URI)?.toUri()
        val mode = intent.getStringExtra(DebugBenchmarkService.EXTRA_MODE)
            ?.let { runCatching { DebugBenchmarkMode.valueOf(it) }.getOrNull() }
            ?: DebugBenchmarkMode.FAST
        val minutes = intent.getIntExtra(DebugBenchmarkService.EXTRA_TARGET_MINUTES, 10)
        if (source?.scheme != "content" ||
            (mode == DebugBenchmarkMode.REALTIME && minutes !in DebugBenchmarkService.allowedTargetMinutes)
        ) {
            Toast.makeText(this, tr("自动化入口需要 content:// URI 和有效模式/时长", "Automation entry requires a content:// URI and valid mode/duration"), Toast.LENGTH_LONG).show()
            return
        }
        startWithGrant(source, mode, minutes)
    }

    private fun startWithGrant(uri: Uri, mode: DebugBenchmarkMode, minutes: Int) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { DebugBenchmarkService.start(this, uri, mode, minutes) }
            .onFailure { Toast.makeText(this, tr("无法开始导入：${it.message ?: "系统拒绝"}", "Could not start import: ${it.message ?: "System denied the request"}"), Toast.LENGTH_LONG).show() }
    }

    @androidx.compose.runtime.Composable
    private fun BenchmarkScreen() {
        var uriString by rememberSaveable { mutableStateOf<String?>(null) }
        val uri = uriString?.toUri()
        var mode by remember { mutableStateOf(DebugBenchmarkMode.FAST) }
        var targetIndex by remember { mutableIntStateOf(0) }
        val targetOptions = remember { DebugBenchmarkService.allowedTargetMinutes.sorted() }
        val state by DebugBenchmarkService.state.collectAsState()
        val recording by RecordingService.state.collectAsState()
        val canStart = !state.running && recording.sessionId == null
        val currentCourse = AsrHotwordPreferences.selectedHotwordCourse(this, tolerateUnreadableCustom = true)
        val currentHotwordsEnabled = AsrHotwordPreferences.isDataStructureEnabled(this)
        val showingCurrentSession = state.running
        val actualCourseName = if (showingCurrentSession) state.courseName ?: currentCourse.name else currentCourse.name
        val actualHotwordsEnabled = if (showingCurrentSession) state.hotwordsEnabled ?: currentHotwordsEnabled else currentHotwordsEnabled
        val modelLabel = if (BuildConfig.ASR_HOTWORD_PROFILE == "data_structure") {
            if (actualHotwordsEnabled) tr("X-ASR ${actualCourseName}热词", "X-ASR $actualCourseName hotwords") else "X-ASR"
        } else BuildConfig.ASR_MODEL_LABEL
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
            if (chosen != null) {
                uriString = chosen.toString()
                runCatching {
                    contentResolver.takePersistableUriPermission(chosen, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(ClassMemoryIcons.Back, contentDescription = tr("返回", "Back")) }
                Text(tr("导入课堂录音", "Import a class recording"), style = MaterialTheme.typography.titleLarge)
            }
            Surface(shape = RoundedCornerShape(22.dp), color = ClassMemoryColors.current.surfaceAlt) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("已有录音，也能留下课堂", "Your recording, ready to revisit"), style = MaterialTheme.typography.titleLarge)
                        Text(tr("WAV / M4A · 手机本地识别", "WAV / M4A · on-device transcription"), color = ClassMemoryColors.current.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    NotebookIllustration(Modifier.size(82.dp))
                }
            }
            if (BuildConfig.DEBUG) Text(tr("模型：$modelLabel", "Model: $modelLabel"))
            if (BuildConfig.ASR_HOTWORD_PROFILE == "data_structure") {
                val timing = if (showingCurrentSession) tr("本次识别", "this run") else tr("下次识别", "next run")
                Text(tr("课程热词：$actualCourseName · ${if (actualHotwordsEnabled) "开启" else "关闭"}（$timing）", "Course hotwords: $actualCourseName · ${if (actualHotwordsEnabled) "On" else "Off"} ($timing)"))
            }
            if (BuildConfig.ASR_MODEL_ID == "qwen3") Text(tr("按语音短段出字，不是逐字实时出字。", "Text appears in short speech segments, not character by character."))
            Text(tr("选择 WAV、M4A 等音频后，本机完成识别并保存为课堂记录；输入音频不会被复制或长期保存。", "Choose WAV, M4A, or another audio file to transcribe it on this device and save it as a class record. The input audio is not copied or retained."))
            OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !state.running, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp), shape = RoundedCornerShape(14.dp)) {
                Text(if (uri == null) tr("选择音频文件", "Choose audio file") else tr("已选择：${uri?.lastPathSegment ?: "音频文件"}", "Selected: ${uri?.lastPathSegment ?: "Audio file"}"))
            }
            if (BuildConfig.DEBUG) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { mode = DebugBenchmarkMode.FAST }, enabled = !state.running) {
                    Text(if (mode == DebugBenchmarkMode.FAST) tr("✓ 普通识别", "✓ Standard") else tr("普通识别", "Standard"))
                }
                OutlinedButton(onClick = { mode = DebugBenchmarkMode.REALTIME }, enabled = !state.running) {
                    Text(if (mode == DebugBenchmarkMode.REALTIME) tr("✓ 压力测试", "✓ Stress test") else tr("压力测试", "Stress test"))
                }
            }
            if (BuildConfig.DEBUG && mode == DebugBenchmarkMode.REALTIME) {
                OutlinedButton(
                    onClick = { targetIndex = (targetIndex + 1) % targetOptions.size },
                    enabled = !state.running,
                ) { Text(tr("目标：${targetOptions[targetIndex]} 分钟（点击切换）", "Target: ${targetOptions[targetIndex]} minutes (tap to change)")) }
            }
            Button(
                enabled = uri != null && canStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
                onClick = { startWithGrant(uri ?: return@Button, mode, targetOptions[targetIndex]) },
            ) { Text(tr("开始本地识别", "Start local recognition")) }
            if (recording.sessionId != null) Text(tr("请先结束正在记录的课堂，再导入录音。", "End the current class before importing a recording."))
            if (state.running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { DebugBenchmarkService.stop(this@DebugBenchmarkActivity) }) { Text(tr("正常停止", "Stop normally")) }
                    OutlinedButton(onClick = { DebugBenchmarkService.cancel(this@DebugBenchmarkActivity) }) { Text(tr("取消", "Cancel")) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = ClassMemoryColors.current.surface, border = BorderStroke(1.dp, ClassMemoryColors.current.border)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("状态：${state.status}", "Status: ${state.status}"), style = MaterialTheme.typography.titleSmall)
                    if (state.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(tr("已处理 ${"%.1f".format(state.inputSeconds)} 秒 · 已保存 ${state.finalCharacters} 字", "Processed ${"%.1f".format(state.inputSeconds)} s · ${state.finalCharacters} characters saved"), style = MaterialTheme.typography.bodyMedium, color = ClassMemoryColors.current.muted)
                }
            }
            state.error?.let { Text(tr("提示：$it", "Notice: $it"), color = MaterialTheme.colorScheme.error) }
            if (!state.running && state.sessionId != null) {
                OutlinedButton(onClick = {
                    startActivity(Intent(this@DebugBenchmarkActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                }) { Text(tr("返回历史课堂", "Back to class history")) }
            }
            if (BuildConfig.DEBUG) state.textReportPath?.let { Text(tr("文本报告：$it", "Text report: $it"), style = MaterialTheme.typography.bodySmall) }
            if (BuildConfig.DEBUG) state.jsonReportPath?.let { Text(tr("JSON 报告：$it", "JSON report: $it"), style = MaterialTheme.typography.bodySmall) }
            if (BuildConfig.DEBUG && !state.running && state.textReportPath != null && state.jsonReportPath != null) {
                OutlinedButton(onClick = { shareReports(state) }) { Text(tr("分享本次测试报告（文字 + 指标）", "Share this test report (transcript + metrics)")) }
            }
        }
    }

    private fun shareReports(state: DebugBenchmarkUiState) {
        lifecycleScope.launch {
            runCatching {
                val uris = withContext(Dispatchers.IO) {
                    val directory = File(cacheDir, "benchmark-exports").apply { mkdirs() }
                    listOfNotNull(state.textReportPath, state.jsonReportPath).map { path ->
                        val source = File(path)
                        require(source.isFile && source.canonicalFile.parentFile == File(filesDir, "benchmark-reports").canonicalFile)
                        val target = File(directory, "${BuildConfig.ASR_MODEL_ID}-${source.name}")
                        source.copyTo(target, overwrite = true)
                        FileProvider.getUriForFile(this@DebugBenchmarkActivity, "$packageName.fileprovider", target)
                    }
                }
                check(uris.size == 2)
                val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/plain"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    clipData = ClipData.newRawUri(tr("ASR 测试报告", "ASR test report"), uris.first()).apply {
                        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, tr("分享 ${BuildConfig.ASR_MODEL_LABEL} 测试报告", "Share ${BuildConfig.ASR_MODEL_LABEL} test report")))
            }.onFailure {
                Toast.makeText(this@DebugBenchmarkActivity, tr("无法分享测试报告", "Could not share test report"), Toast.LENGTH_LONG).show()
            }
        }
    }
}

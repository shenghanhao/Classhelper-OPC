# 课后练习 / After-class practice

Included in the locally built OPC `0.4.0-opc.6` preview. See [CURRENT.md](../CURRENT.md) for validation and delivery status; building locally does not update the GitHub Release.

## 使用方式

完成课堂复盘后，滚动到页面末尾的「课后练习」，或通过右下角「目录」跳到这里。选择「生成课后练习」并确认发送后，DeepSeek 按本课知识量生成题目，每题归属一个已有知识块。当前需要设置为「手机直连 DeepSeek」；旧自建代理不提供这组新接口。

自 0.4.1 起的生成规则如下；每批选择题和简答题都要存在，数量最多相差一道。首选偶数题以保持 1:1，同时接受范围内的合理奇数。既有题目不会因此重写。

| 有效知识块 | 新题数量 | 首选数量 |
| --- | --- | --- |
| 1–2 | 2–3 | 2 |
| 3–4 | 3–4 | 4 |
| 5–7 | 5–6 | 6 |
| 8 或更多 | 7–8 | 8 |

没有知识块时不请求模型出题。每题和每条 QA 默认展开，可单独折叠；收起只隐藏正文，保留作答草稿和记录。

- 选择题列出选项，但没有单选按钮。在同一个「我的作答」框内输入选项、为什么选择它，以及其他选项为什么不对。题干和评阅提示词都要求检查选择和排除理由。
- 简答题也使用这个输入框，填写对知识点的理解和解题过程。文本长度没有应用侧截断，输入框可内部滚动。
- 点击「提交作答」并确认后，DeepSeek 结合完整课堂、知识块、已保存的板书分析和学生作答评阅。结果包含正确、部分正确、错误或不清楚的判断，以及反馈和正确思路。提示词要求独立检查知识正确性，不能把转写错误当标准答案，也不能只凭选项碰巧正确就判为理解正确。
- 对错误、部分正确或表述不清的回答，可在练习区末尾追加一道只针对当前薄弱知识的题。提示词要求更换情境、辨错、反例、计算/解释或切换题型，不只是重复原题或换数字。每道原题最多追加一次，追加题不再追加；正确答案不补题。不在后台连续推送题目。
- 新题保存后显示一次消息「DeepSeek 已追加一道针对练习，已放到末尾」，可点「查看」跳过去。已消费的提醒不会在重开后重复弹出；提醒不是系统推送。
- 每次完成的评阅会把题目、学生答案、反馈和正确思路保存为对应知识块下面的一条 QA。多次不同作答保留各自记录，失败时可用原作答 ID 重试。重试旧答案不会覆盖正在编辑的新草稿。

作答草稿自动保存在本机。失败或取消后已提交的答案仍保留；完成的评阅不因滚出屏幕或重开页面而丢失。课堂内容发生变化时，旧版本练习和 QA 仍可读，需要更新复盘后才能提交新请求。

模型调用会使用个人 DeepSeek 账户并产生费用；生成和提交前均有发送确认。请求不设置应用侧 `max_tokens`，供应商限制仍适用。练习复用此前保存的板书分析文字，不重新上传照片。真实模型的题目质量和评阅正确性尚未通过本轮线上调用验证。

## Implementation and contracts

- `review/ReviewExercises.kt`: typed exercises, attempts and one-time notices; strict JSON codecs and validation. Kinds are `MCQ` and `SHORT`. Grading states are `pending`, `failed`, and `graded`; verdicts are `correct`, `partial`, `incorrect`, and `unclear`.
- `review/ReviewContracts.kt`: optional exercise and notice lists on `ReviewRun`. Existing snapshots without these fields remain readable; this feature does not change input fingerprints or Room schemas.
- `review/ReviewCoordinator.kt`: gateway contracts. The direct provider implements generation/grading; the proxy reports these operations as unsupported.
- `review/DirectDeepSeekGateway.kt`: full-context prompts, response validation and separation of untrusted source/answer text from instructions. The application creates IDs, not the model.
- `review/ReviewRuntime.kt`: serializes QA/practice writes, persists pending attempts before requests, rejects stale snapshots, validates source references and archives graded attempts as unit QA. Duplicate submissions and retries cannot duplicate follow-ups or QA records.
- `review/ReviewStore.kt`: atomic snapshot writes plus separate draft files keyed by snapshot and exercise. Drafts do not enter snapshot discovery, and deleting a class removes its review directory as before.
- `ui/ReviewExercises.kt` and `ui/ReviewScreen.kt`: text-only answer entry, consent, feedback/history, retry, cancellation, draft recovery and one-time in-app notices.
- `ui/ReviewNavigation.kt` and `ui/ReviewQuickDirectory.kt`: stable list positions and a practice shortcut. Topic keys are namespaced to avoid collisions with fixed navigation entries.

All exercise records are local to their review snapshot. Existing Markdown/Notebook classroom exports are unchanged by this task; practice QA is shown and stored in the review experience.

## Verification

For the local 0.4.1 delivery, 36 tests passed across the exercise, direct-provider, QA, navigation, exercise-UI and rendering-cache suites. The count/mix boundaries and legacy five-question snapshots are covered. OPC assembly and signature verification passed; phone testing/installation was deferred at the user's request. No live model requests were made.

On 2026-11-05, 31 targeted JVM tests passed across `ReviewExercisesTest`, `DirectDeepSeekGatewayTest`, `ReviewQaGraphTest`, `ReviewLegacyV2Test`, `ReviewTextV3Test`, `ReviewVisionTest`, `ReviewNavigationTest`, and `ReviewExerciseUiTest`. OPC assembly passed in the same run. Coverage includes persisted readback, legacy decoding, retries, cancellation, duplicate prevention, bounded follow-ups, source changes, notice consumption and navigation/input logic.

No live provider calls or device UI tests were performed for this delivery. Mock success does not establish real model accuracy or on-device usability.

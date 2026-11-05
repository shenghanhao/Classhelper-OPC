# study-agent-server

ClassMemory AI 的轻量分析代理。Android 只访问这个服务；模型供应商密钥只存在于服务端环境变量，不进入 APK、Git 或日志。

## 职责

- `POST /v1/study/analyze`：接收已脱敏的课堂文字快照，调用一家 OpenAI-compatible 模型，返回严格结构化的总结与可溯源知识点。
- `POST /v2/study/review`：分阶段生成整课导览/语义分区（outline）、简明知识单元（unit）、完整复习方案与简单日期建议（plan），协议见根目录 `docs/REVIEW_V2_CONTRACT.md`。
- 服务端再次校验请求字段类型、唯一 ID 和原文关联。V1 的 evidence 必须逐字来自所选 segment；V2 校验完整分区、知识点/图片引用、全单元方案覆盖。任何问题拒绝当前阶段，不伪造修补。
- V1 响应带 `schemaVersion`、`sourceHash`、`skillVersion`；V2 返回阶段对象，Android 编排器将其绑定到当前内容指纹并独立复验。
- 默认单次供应商超时 180 秒、最多 2 个并发、每 IP 每小时 300 个阶段请求、全局每天 2000 个阶段请求；可通过环境变量调整。阶段限流不是金额预算，一个 outline 请求可能包含多次供应商调用。
- 不设业务输入/输出字数、段数、知识点数量或响应字节上限，也不发送固定 `max_tokens`。模型自身仍有上下文与输出容量；供应商以 `finish_reason: length` 返回时明确报错，不保存部分结果。请求频率、并发、超时控制保留。大内容会占用相应内存；V2 导览按完整原文片段分批处理再合并，同一知识点可关联多个区间。不会静默裁剪单个超长片段；若单片段或综合材料超过实际模型容量，会明确失败，未实现自动续写。
- 不记录课堂正文。成功只记录 requestId、状态码、耗时、provider 状态与 token 用量；错误只记录错误码和堆栈。

## 运行

要求 Node.js 20+，无第三方依赖。

```bash
cd class-memory/study-agent-server
STUDY_PROVIDER_URL="https://api.deepseek.com/chat/completions" \
STUDY_PROVIDER_API_KEY="..." \
STUDY_MODEL="deepseek-flash" \
node server.mjs
```

健康检查：

```bash
curl -s http://127.0.0.1:8787/healthz
```

本地测试：

```bash
node --test test/server.test.mjs test/review-v2.test.mjs
```

## 配置

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `STUDY_AGENT_HOST` | `127.0.0.1` | 监听地址；真机同局域网测试时改为 `0.0.0.0` |
| `STUDY_AGENT_PORT` | `8787` | 端口 |
| `STUDY_PROVIDER_URL` | 空 | OpenAI-compatible chat completions URL |
| `STUDY_PROVIDER_API_KEY` | 空 | 供应商密钥，只放环境或私有配置 |
| `STUDY_MODEL` | `deepseek-flash` | 单一模型名；实际可用性由供应商决定 |
| `STUDY_ACCESS_TOKEN` | 空 | 可选，设置后要求 `Authorization: Bearer <token>` |
| `STUDY_PROVIDER_TIMEOUT_MS` | `180000` | 单次供应商请求超时；V2 包含读取响应正文 |
| `STUDY_RATE_LIMIT_PER_HOUR` | `300` | 每 IP 每小时阶段请求上限 |
| `STUDY_DAILY_LIMIT` | `2000` | 全局每日阶段请求上限 |
| `STUDY_MAX_CONCURRENT` | `2` | 并发上限 |
| `STUDY_PROVIDER_JSON_MODE` | `true` | 发送 `response_format: json_object`；供应商不支持时设为 `false` |
| `STUDY_REVIEW_BATCH_CHARS` | `60000` | V2 导览分批处理目标，不是总输入上限；保留每个原文片段，最后综合所有批次 |

## V2 引用与结果验证

- 课程背景、历史原文、笔记均须由客户端展示并取得同意后发送；代理不能验证设备内课程归属，客户端负责仅选择同一课程的历史资料。
- outline 的 sections 必须按时间顺序覆盖全部本课 sourceIds，恰好一次。topics 可以跨多个 sections 合并重复知识点，每个区间必须有主题。
- 供应商内部导览协议使用批内从 0 开始的整数 `index`，分区返回含两端的 `startIndex` / `endIndex`。服务端严格检查连续、无重叠、无遗漏和边界后还原原始 `sourceIds`；主题和跨批合并只选择 `sectionIds`，程序展开已验证分区的来源。App 的 V2 协议不变，单元仍由模型选择实际引用。坏区间或遗漏分区会失败，不自动补齐，原文不裁剪。
- unit 只要求解释、适用条件（不适用可空）、课堂重点；例子没有则 `null`。每个单元至少引用对应主题的一条本课原文，历史原文可作为补充。
- photoIds 必须存在于用户提供的图片索引；只发送时间戳和标识符，不发送像素或文件路径。图片引用表示课堂时间相关，不表示模型已经理解图片。
- plan 必须覆盖全部成功知识单元，日期不早于本次输入的 today。已复习状态由 Android 本地保存，模型不能生成或覆盖。
- 格式正确不等于内容正确，仍需要用户核对原文。无依据的示例或解释属于模型质量问题，不能仅凭结构测试宣称准确。

## 安全边界

- 密钥只在服务端；不要把 `STUDY_PROVIDER_API_KEY` 写进 Gradle、BuildConfig、APK 或 Git。
- 共享 `STUDY_ACCESS_TOKEN` 会进入客户端时同样可被提取；它只适合临时试用控制，不能当作强身份认证。正式分发前应换成按安装签发的短期凭证、服务端配额与撤销机制。
- 当前按来源 IP 限流。若部署在反向代理后，需只信任受控代理写入的客户端 IP，不能直接相信任意 `X-Forwarded-For`。
- Node 服务本身监听 HTTP；部署时需由 HTTPS 反向代理终结 TLS。Android V2 只接受 HTTPS 地址，本机合成验收脚本可以访问回环 HTTP。
- 课堂文字默认不在服务端落盘；如要保存用于评估，必须另行取得用户授权并明确保留期限。

## 与 Android 的约定

V2 在 App 设置 → 复习服务中填写 HTTPS 代理根地址与可选代理令牌，无需重新构建来更换地址。不要填供应商 API Key。HTTP 客户端连接超时 15 秒、写入 120 秒、读取/单阶段总调用 30 分钟，适应全课分批导览；供应商每次请求仍默认 180 秒。失败不自动重试，取消会关闭连接；成功阶段原子保存，显式重试仅补充失败/缺失部分。

以下为旧 V1 构建配置方式；它不代替 V2 设置页面：

构建 OPC 渠道时传入代理地址：

```bash
./gradlew :app:assembleOpc \
  -PasrModel=xasr \
  -PasrHotwordProfile=none \
  -PstudyAgentUrl=https://your-proxy.example.com
```

客户端只接受 `https://` 地址；单次调用 60 秒总超时，不自动重试，取消时关闭连接。返回结果必须通过 `StudyContracts` 的 schema、sourceHash、ID 关联与 evidence 逐字来源校验，否则整包丢弃。

## V3 讲解

`/v3/study/review` 独立于 V2，使用课堂文字、学生疑问和注释生成 Markdown 讲解及复习方案。板书仅作为本地时间索引，不发送图片像素。协议见 `docs/REVIEW_V2_CONTRACT.md`。

### V3 问答、笔记与关系图

均使用 `POST /v3/study/review`，沿用 V3 的完整 `input`、已验证 `outline` 和完整 `units`；原文及单元正文不裁剪，照片仅传索引。所有阶段失败时保留客户端现有内容，可重试；供应商输出不完整会明确报错。错误的模型引用或结构会自动重试一次，仍错误则返回错误，不接受部分结果。

- `stage: "qa"` 另需 `targetUnitId`（单元 ID，或 `null` 表示全课）、`question`、`history: [{question, answer}]`（仅当前问题的追问历史）、`noteIds`（相关原始笔记 ID 数组）。响应 `{answerMarkdown, sourceIds, updates: [{unitId, markdown}], clarificationNeeded}`。正文区分老师原话和 AI 解释，来源仅引用已提供 source ID。单元问题的更新仅能指向该单元，markdown 是融合后的完整正文；全课问题的 updates 必须为空。需要澄清时不得更新正文。
- `stage: "notes"` 返回 `{items: [{noteId, disposition, unitId, relatedQuestionNoteId, answerMarkdown, sourceIds}], updates: [{unitId, markdown}]}`。每条原始笔记恰好出现一次；`disposition` 为 `answered`、`clarify`、`context`。疑问不得标为 context；answered 必须指向已有单元、附来源和对应融合正文；clarify 请求补充且不指定单元；中性或作为疑问补充的注释用 context；后者以 `relatedQuestionNoteId` 指向同次输入的原 QUESTION noteId，普通注释为 null。更新正文必须与原正文不同；来源仅允许本课原文。客户端用原 noteId 去重并保存问答，仅应用明确返回的更新，保留其他单元及已有图片、原文索引。语义正确性仍依赖模型，结构校验不能保证答案质量。
- `stage: "graph"` 返回 `{nodes: [{unitId}], edges: [{fromUnitId, toUnitId, relation}]}`，节点恰好覆盖已知单元；关系为 `derives`（前者由后者推导）、`depends_on`（前者依赖后者）、`parallel` 或 `related`。服务只返回数据，由客户端渲染；无关系时 edges 可为空。

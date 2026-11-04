# study-agent-server

ClassMemory AI 的轻量分析代理。Android 只访问这个服务；模型供应商密钥只存在于服务端环境变量，不进入 APK、Git 或日志。

## 职责

- `POST /v1/study/analyze`：接收已脱敏的课堂文字快照，调用一家 OpenAI-compatible 模型，返回严格结构化的总结与可溯源知识点。
- 服务端再次校验：请求字段/长度/ID、evidence 是否逐字来自所选 segment、知识点数量与重复 ID。任何问题整包拒绝，不修补。
- 每个响应把 `schemaVersion`、`sourceHash`、`skillVersion` 绑定到本次请求，客户端仍会独立复验。
- 默认单次供应商超时 55 秒、最多 2 个并发、每 IP 每小时 20 次、全局每天 100 次；可通过环境变量调整。
- 不记录课堂正文。成功只记录 requestId、状态码、耗时、provider 状态与 token 用量；错误只记录错误码和堆栈。

## 运行

要求 Node.js 20+，无第三方依赖。

```bash
cd class-memory/study-agent-server
STUDY_PROVIDER_URL="https://api.deepseek.com/chat/completions" \
STUDY_PROVIDER_API_KEY="..." \
STUDY_MODEL="deepseek-chat" \
node server.mjs
```

健康检查：

```bash
curl -s http://127.0.0.1:8787/healthz
```

本地测试：

```bash
node --test test/server.test.mjs
```

## 配置

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `STUDY_AGENT_HOST` | `127.0.0.1` | 监听地址；真机同局域网测试时改为 `0.0.0.0` |
| `STUDY_AGENT_PORT` | `8787` | 端口 |
| `STUDY_PROVIDER_URL` | 空 | OpenAI-compatible chat completions URL |
| `STUDY_PROVIDER_API_KEY` | 空 | 供应商密钥，只放环境或私有配置 |
| `STUDY_MODEL` | `deepseek-chat` | 单一模型名 |
| `STUDY_ACCESS_TOKEN` | 空 | 可选，设置后要求 `Authorization: Bearer <token>` |
| `STUDY_PROVIDER_TIMEOUT_MS` | `55000` | 供应商请求超时 |
| `STUDY_RATE_LIMIT_PER_HOUR` | `20` | 每 IP 每小时请求上限 |
| `STUDY_DAILY_LIMIT` | `100` | 全局每日请求上限 |
| `STUDY_MAX_CONCURRENT` | `2` | 并发上限 |
| `STUDY_PROVIDER_JSON_MODE` | `true` | 发送 `response_format: json_object`；供应商不支持时设为 `false` |

## 安全边界

- 密钥只在服务端；不要把 `STUDY_PROVIDER_API_KEY` 写进 Gradle、BuildConfig、APK 或 Git。
- 共享 `STUDY_ACCESS_TOKEN` 会进入客户端时同样可被提取；它只适合临时试用控制，不能当作强身份认证。正式分发前应换成按安装签发的短期凭证、服务端配额与撤销机制。
- 当前按来源 IP 限流。若部署在反向代理后，需只信任受控代理写入的客户端 IP，不能直接相信任意 `X-Forwarded-For`。
- 服务只接受 HTTPS 客户端，但本机开发可以用 HTTP；OPC 分发 APK 必须配置 HTTPS 地址。
- 课堂文字默认不在服务端落盘；如要保存用于评估，必须另行取得用户授权并明确保留期限。

## 与 Android 的约定

构建 OPC 渠道时传入代理地址：

```bash
./gradlew :app:assembleOpc \
  -PasrModel=xasr \
  -PasrHotwordProfile=none \
  -PstudyAgentUrl=https://your-proxy.example.com
```

客户端只接受 `https://` 地址；单次调用 60 秒总超时，不自动重试，取消时关闭连接。返回结果必须通过 `StudyContracts` 的 schema、sourceHash、ID 关联与 evidence 逐字来源校验，否则整包丢弃。

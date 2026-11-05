# Dot 接手指南 / Continue in Dot

工作分支 `codex/opc-qa-graph`，基于公开分支 `codex/opc-public` 的 `0485f4b`。从此分支打开仓库可以同时修改 Android 客户端和配套服务。向默认分支提交更改走 Pull Request，由维护者审核。

## 已实现的行为

- 复盘页右下角的问号默认向整节课提问，也可以指定一个知识单元。请求包括本节课完整转写、所有单元标题、选中单元的讲解和当前这串追问。已保存的单元问答可以在单元下展开，全课问答在页面底部。
- 每个问题及每轮追问有独立 ID。回答、来源和各次讲解修订保存在本机。失败记录可以按原 ID 重试。
- 生成复盘时处理课堂原有的疑问。能定位的疑问要求模型在相应单元中展开讲解；相关注释可以补充疑问，中性注释只提供背景。证据不足时请求澄清。
- 知识图由模型返回单元之间的关系，Android 本地绘制。可以拖动、缩放、点节点回到对应单元。图片保留课堂时间索引，本轮只做文本分析。

## 从哪里读

| 工作内容 | 仓库入口 |
| --- | --- |
| 界面、问号、问答历史、知识图 | `class-memory/android/app/src/main/java/com/classmemory/app/ui/ReviewScreen.kt`、同目录 `ReviewQuestions.kt` |
| 请求编排、持久化、版本恢复 | `class-memory/android/app/src/main/java/com/classmemory/app/review/` 下的 `ReviewCoordinator.kt`、`ReviewRuntime.kt`、`ReviewStore.kt` |
| 阶段请求和校验 | 同目录 `ReviewContracts.kt`、`ReviewHttpGateway.kt`，服务端 `class-memory/study-agent-server/review-v3.mjs` |
| 课程上下文、模型和构建准备 | [课程上下文](docs/COURSE_CONTEXT.md)、[ASR 权重](docs/MODELS.md)、[Android 安装包](docs/OPC_APK_DELIVERY.md) |
| 自建复盘服务 | [服务配置和 V3 请求协议](class-memory/study-agent-server/README.md) |

## v0.3.3 手机直连 DeepSeek（本地安装）

用户选择自行填写 API Key，替代已经停止的电脑临时代理。设置 → 复习服务连接 → 手机直连 DeepSeek，输入个人密钥并保存；默认 deepseek-flash，也支持 deepseek-v4-pro。密钥通过 Android Keystore 的 AES-GCM 密钥加密后存储，不进入课堂记录、导出、错误提示或源码；设置页禁止截图。清除密钥不删除课堂资料。旧 localhost 配置默认切换到直连，旧远程代理保留，代理 token 不会成为供应商 API Key。

`DirectDeepSeekGateway.kt` 和 `ReviewRuntime.kt` 接通导览、知识单元、课堂标注、复习计划、问答和知识图。完整课堂输入不截断，请求不设应用侧 max_tokens；供应商容量限制仍适用。长课导览使用连续索引区间，严格验证后还原原文 ID，拒绝漏段、重叠和越界。失败重试沿用本地已保存阶段。无效/截断响应不作为成功结果保存；请求可取消，密钥不随重定向发送。

连接与存储入口：`review/ReviewConnection.kt`、`ReviewCredentialCipher.kt`；设置与发送预览：`ui/ReviewConnectionSettings.kt`、`ReviewScreen.kt`。标准 HTTPS 证书验证保持不变，未引入测试 CA。调用协议参考 [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)。

验收：DirectDeepSeekGatewayTest 7 项、ReviewConnectionTest 4 项、ReviewPipelineTest 13 项、ReviewQaGraphTest 6 项，共 30 项一次通过；assembleOpc 成功。`0.3.3-opc.5 / 10005` 已覆盖安装，证书与上一版一致。真机用非真实占位密钥验证保存、冷启动解密和清除，全部通过，测试值已清除。未发送真实模型请求；用户选择自行填写密钥并测试，真实整理质量和线上连通性未宣称验收。未发布新 GitHub Release，公开下载仍为 v0.3.1。

回退分支：`codex/checkpoint-before-direct-deepseek`，指向 `6247c1d`。本次 APK SHA-256：`04fa831efe23cf2e21b210c03bb83795532fca0d9d3ade63c34aa5bcc78cd78c`。

## v0.3.2 本地真机修复

语言切换问题已在 Android 真机复现。v0.3.1 点击英文菜单中的「切换 中文」会因 `ChildSemanticsNode.applySemantics` 抛出 `visitAncestors called on an unattached node` 而退出。

当前实现改为只保存语种偏好，然后请求 Activity 重建。在新界面 `setContent` 前载入保存语种，点击时保留旧界面的语言、Locale 和菜单状态，移除按语言变化强制替换界面的 `key`。

OPC `0.3.2-opc.4` / code `10004` 已构建并覆盖安装。语言专项测试 3 项全部通过，真机完成「英文→中文→英文→中文」且应用进程持续存活，停止进程后冷启动仍为中文。验收后手机留在中文首页。本版目前为本地安装修复，GitHub Release 仍为 v0.3.1。

## v0.3.1 修复与交接

**发布情况。** OPC 安装包已公开并核对 GitHub 摘要和匿名下载入口。Debug 包已构建并通过同样的本地检查，但上传发生两次连接 EOF，按重试约定已停止。Release 当前包含 OPC 及其 SHA-256。恢复发布时只需补传同版 Debug，或从代码提交重新构建并验证后上传。请先确认重试授权。


本次代码提交为 `26896d33b7963cb27e9844aa11e4d54aef648c50`，发布标签为 `v0.3.1`。[APK 下载](https://github.com/shenghanhao/Classhelper-OPC/releases/tag/v0.3.1)

- 首页名称固定为「课堂记忆 / Class Memory」。语言菜单显式选择目标语种，重新生成当前页面内容；页面位置和复盘状态保留在重绘范围之外。沿用原语言偏好设置。
- OPC 和 Debug 首页均提供「导入课堂录音」。既有 Decoder、Activity、Service 从 `src/debug` 移到 `src/main`，沿用本地识别及 Room 存储。录音与文件导入互斥，后台导入可从通知返回。
- OPC 导入界面使用完整文件普通识别。Debug 保留压测、报告和外部自动化入口。OPC 导入组件只允许应用内部打开。
- 应用 ID 沿用前版。Debug 升为 `0.3.1` / code `3`，OPC 升为 `0.3.1-opc.3` / code `10003`。

Debug 和 OPC 安装包均构建成功，签名证书与 v0.3.0 一致。已检查两份合并清单及 APK 类定义中的导入入口和后台服务。

专项验证已通过 `AppLanguageTest` 三项及 `DebugAudioDecoderResamplerTest` 两项。语言测试覆盖来回切换、持久化和重新初始化。此前现场语言切换异常的具体触发条件仍待真机确认。

从 `class-memory/android` 运行本次检查

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'com.classmemory.app.i18n.AppLanguageTest' \
  --tests 'com.classmemory.app.benchmark.DebugAudioDecoderResamplerTest' \
  -PasrModel=xasr -PasrComparison=true -PasrHotwordProfile=none
```

下一次手机验收从首页三点菜单开始，依次切换中文、英文、中文，重启后确认语种；再选择一份录音进行导入，检查历史文字和详情导出。一次导入结束后可开始正常麦克风课堂。

## v0.3.0 验证记录

v0.3.0 安装包取自公开代码提交 `c8e2ff5203cdcff8c2ce5ff2bd02324d191f151b`。发布标签 `v0.3.0` 包含同一套代码和本交接记录。[APK 和校验文件](https://github.com/shenghanhao/Classhelper-OPC/releases/tag/v0.3.0)

| 验收项目 | 结果 |
| --- | --- |
| Android 定向测试 | 20 个不同用例全部通过。首次通过 17 项，其余 3 项补齐 JVM 测试主线程配置后通过。覆盖复盘流程、问题持久化与重试、并发问答、过期知识图和图布局。 |
| 服务端模拟验证 | 合成输入覆盖 V3 HTTP 接口、问答上下文与来源、课堂疑问和注释的归属与正文更新、无效图节点的拒绝。 |
| Android 构建 | 从上述公开源代码成功构建 Debug 和 OPC。两份 arm64 APK 均通过 Android 签名校验，已核对应用 ID、版本、内置 X-ASR 权重和公式渲染资源。 |

在 Dot 中拉取下列开发分支即可继续提交。重现当前发布使用标签 `v0.3.1`。

```sh
git clone --branch codex/opc-qa-graph --single-branch https://github.com/shenghanhao/Classhelper-OPC.git
```

## 本地验证与下一步

服务端运行命令使用 Node.js 20 或更高版本。从仓库根目录执行以下模拟测试即可检查问答、笔记、知识图和 V3 HTTP 入口，无需供应商密钥。

```sh
cd class-memory/study-agent-server
node --test test/review-v3-extra.test.mjs test/review-v3-notes-validation.test.mjs test/review-v3-http.test.mjs
```

新环境先准备 Android SDK 和 X-ASR 权重，再从 `class-memory/android` 执行本次改动对应的检查。

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'com.classmemory.app.review.ReviewPipelineTest' \
  --tests 'com.classmemory.app.review.ReviewQaGraphTest' \
  --tests 'com.classmemory.app.ui.ReviewGraphLayoutTest' \
  -PasrModel=xasr -PasrComparison=true -PasrHotwordProfile=none
```

本次从同一 Android 目录构建，使用 JBR 25、Android SDK 36.1 和仓库自带的 Gradle。首次构建需要联网下载依赖。

```sh
./gradlew :app:assembleDebug :app:assembleOpc -PasrModel=xasr -PasrComparison=true -PasrHotwordProfile=none
```

**后续线上验收（用户自行进行）。** 手机已安装独立 OPC 0.3.3。在复习服务设置填写个人 DeepSeek API Key，保存后回到失败的课堂，预览并重试失败部分。核对单元讲解，再验证点图跳转、追问和重开查看。用户若选择自建代理模式，供应商密钥仍仅放在自己的服务端环境变量。

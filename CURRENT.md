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

## 此版本的交付与验收

本次安装包取自公开代码提交 `c8e2ff5203cdcff8c2ce5ff2bd02324d191f151b`。发布标签 `v0.3.0` 包含同一套代码和本交接记录。[APK 和校验文件](https://github.com/shenghanhao/Classhelper-OPC/releases/tag/v0.3.0)

| 验收项目 | 结果 |
| --- | --- |
| Android 定向测试 | 20 个不同用例全部通过。首次通过 17 项，其余 3 项补齐 JVM 测试主线程配置后通过。覆盖复盘流程、问题持久化与重试、并发问答、过期知识图和图布局。 |
| 服务端模拟验证 | 合成输入覆盖 V3 HTTP 接口、问答上下文与来源、课堂疑问和注释的归属与正文更新、无效图节点的拒绝。 |
| Android 构建 | 从上述公开源代码成功构建 Debug 和 OPC。两份 arm64 APK 均通过 Android 签名校验，已核对应用 ID、版本、内置 X-ASR 权重和公式渲染资源。 |

在 Dot 中拉取下列开发分支即可继续提交。重现本次发布使用标签 `v0.3.0`。

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

**下一步验收。** 在测试手机安装独立 OPC 包，设置里连接自建的 HTTPS 复盘服务。使用自编课堂，记录一个疑问和一条补充注释。生成复盘后核对单元讲解，再依次验证点图跳转、追问、关闭页面后重开查看。真实模型的回答质量和真机操作体验待这一轮验证。模型密钥仅配置到服务端环境变量。

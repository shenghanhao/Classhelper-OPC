# OPC APK 统一产出约定

状态：设计约定，尚未实现 Gradle 渠道、构建脚本或签名配置；本次未产出 APK。禁止将现有 `assembleDebug` 的公开版输出冒充 OPC 版分发。

## 应用身份与并存

| 项目 | 约定 |
| --- | --- |
| 工程入口 | 仓库内 `class-memory/android/`；不使用外层旧模板工程 |
| 原公开版 applicationId | `com.classmemory.app.publish`，保留 |
| OPC applicationId | 固定为 `com.classmemory.app.publish.opc2026`，不随分支名/提交变化 |
| OPC 显示名 | `ClassMemory AI · OPC`，增加可辨认图标角标 |
| Kotlin namespace | 仍为 `com.classmemory.app`，无需为并存重命名源码包 |
| 平台与识别 | Android 10+、arm64；固定 X-ASR，热词默认 none，首版不并发维护三模型包 |
| 数据 | 独立应用私有目录/数据库/偏好；不自动迁移或读取原应用数据 |
| Provider | 维持 `${applicationId}.fileprovider`；检查合并 Manifest 中无旧版固定 authority/共享 UID |
| 版本 | 独立渠道，从 versionCode 10001 起单调增加；versionName `0.2.0-opc.1` 起 |

不同 applicationId 是并存的关键，改 APK 文件名、签名或桌面名称都不能替代。原自用版确切 ID 本轮未读取私人历史；首次出包须列出测试机现有原版包名并确认与 OPC 不同，不能仅凭文档保证与所有旧版均无冲突。

## 实现时统一的构建入口

在 app Gradle 中新增专用 `opc` build type（基于 release），设 `applicationIdSuffix = ".opc2026"`、专用应用名/版本、固定签名配置、release 依赖 fallback。为其配置独立 INTERNET 权限覆盖，沿用本地 ASR；只在 OPC 渠道开放可选云端分析。不得直接把 defaultConfig 的公开版 ID 改成 OPC。

目标命令如下；**目前 `assembleOpc` 不存在，必须完成 M0 后才可使用**，不要失败后改用 `assembleDebug` 替代：

```sh
# 从仓库根目录进入真正 Android 工程；准备模型参照 docs/MODELS.md。
cd class-memory/android
./gradlew :app:assembleOpc -PasrModel=xasr -PasrComparison=true -PasrHotwordProfile=none
```

目标原始输出：`class-memory/android/app/build/outputs/apk/opc/app-opc.apk`，以届时 Gradle 实际产物为准。模型权重不在 Git，缺失则先按模型文档准备，不把普通 clone 宣称为开箱可出包。使用仓库 wrapper、团队固定 JDK 21、所需 Android SDK 36.1；记录实际工具和模型文件校验值。首次构建可正常解析锁定依赖，不默认 offline，不 clean、不擅自更新依赖锁。

首次配置优先由所有者在固定机器出包，避免先搭 CI 和共享签名秘密。自动化出包如后续确有需要，再独立设计受控流水线；不让不可信 PR 获得签名密钥。

## 签名和可升级性

- 建立 OPC 专用私有 keystore，由所有者保管并备份，与原应用签名分开；路径、密码从本机私有配置提供，不入 Git，不硬编码进 APK，不向贡献者分发。
- 缺失签名配置时让 OPC 分发构建失败，禁止退回每台机器不同的 debug 签名。开发者本地实验包不作为统一分发件。
- 同一 OPC applicationId 的后续更新必须使用同一签名证书、更高 versionCode；记录证书 SHA-256 指纹，不记录秘密。
- Android 安装校验并存与更新是后续验收，不在本次规划中执行。禁止卸载原应用「解决冲突」或清除原数据。

## 交付清单与发布权限

所有者从已审阅、工作区干净的明确提交构建一次。产物命名：`ClassMemoryAI-OPC-<version>-<shortSHA>-arm64.apk`；APK 不提交到 Git。

每个候选包同时交付：

1. APK、APK SHA-256 校验文件。
2. 构建清单：源码提交、分支、构建时间、完整构建参数、工具版本、模型标识/校验值、applicationId、版本号、签名证书指纹；服务地址只能是无秘密的基础 URL。
3. 最小验收记录与已知限制，明确哪些是样例模式/真实服务；仅记录实际运行结果。

本地使用 SDK 的 `apkanalyzer manifest application-id` / `apksigner verify --print-certs` 等检查 APK ID 与签名，校验不符即停止分发。经确认后的设备验收：原版与 OPC 同时存在 → 两者各自启动 → 原数据仍在 → OPC 下一版本覆盖升级后学习记录保留。不擅自扩大为完整 ASR/全仓库验收。

APK 通过私有协作仓库的手动 Release 或用户另行授权的参赛渠道交付，附校验值；不发布到原公开仓库 Release。标签如 `opc-v0.2.0-test.1` 指向确切候选提交，由所有者手动发布。私有 Release 本身不能让评委免登录下载，正式提交按赛事平台要求附 APK 或经授权的分发方式，提前验证可获取性。

## 参考

- [Android 构建变体与 applicationIdSuffix](https://developer.android.com/build/build-variants)
- [Android 签名与更新](https://developer.android.com/studio/publish/app-signing)
- 本仓库 `docs/MODELS.md`、`docs/PUBLISH_GUIDE.md`；OPC 计划见 `class-memory/docs/exec-plans/opc-study-agent.md`。

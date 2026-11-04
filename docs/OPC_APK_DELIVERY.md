# OPC test APK / OPC 测试安装包

The OPC build channel exists. From `class-memory/android`, with the model assets documented in `docs/MODELS.md` available, run:

```sh
./gradlew :app:assembleOpc -PasrModel=xasr -PasrComparison=true -PasrHotwordProfile=none
```

Output: `app/build/outputs/apk/opc/app-opc.apk`.
Application ID: `com.classmemory.app.publish.opc2026`.
Version: `0.2.0-opc.1` / versionCode `10001`.
Platform: Android 10+ on arm64.

This channel currently uses the local Android debug signing certificate and inherits release runtime settings. It is a **debug-signed development preview**, not the `debug` Gradle build type or a production release. It does not enable the Debug-only audio-import UI. A stable private signing key and increasing version codes are required for future production distribution. Never commit signing keys or passwords.

Each Release should identify the exact source tag, build parameters, SHA-256 checksum and known limitations. Do not claim device installation or functional acceptance unless actually performed. The current study-analysis UI is not implemented. Do not configure a public model proxy until authentication and spending controls are ready.

中文：OPC 构建渠道已实现，当前使用 Debug 证书签名，运行配置继承 release；因此是测试签名预览包，不等同于 Gradle 的 debug 版本。公开版与 OPC 的应用 ID 不同。正式发行前仍需固定专用签名和并存、覆盖升级验收。Release 应如实记录实际构建和验证结果。

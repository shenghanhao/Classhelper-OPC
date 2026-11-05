# OPC test APK / OPC 测试安装包

The OPC build channel exists. From `class-memory/android`, with the model assets documented in `docs/MODELS.md` available, run:

```sh
./gradlew :app:assembleOpc -PasrModel=xasr -PasrComparison=true -PasrHotwordProfile=none
```

Output: `app/build/outputs/apk/opc/app-opc.apk`.
Application ID: `com.classmemory.app.publish.opc2026`.
Current source version: `0.4.0-opc.6` / versionCode `10006`. The Debug channel is `com.classmemory.app.publish`, version `0.4.0`, versionCode `6`. Only OPC was assembled for this delivery. GitHub Release is still v0.3.1; consult CURRENT.md for local build evidence.
Platform: Android 10+ on arm64.

This channel currently uses the local Android debug signing certificate and inherits release runtime settings. It is a **debug-signed development preview**, not the `debug` Gradle build type or a production release. Both packages offer **⋯ → Import class recording**, which transcribes an existing audio file and saves the resulting class in History. A stable private signing key and increasing version codes are required for future production distribution. Never commit signing keys or passwords.

GitHub v0.3.1 includes the OPC APK and its SHA-256 checksum; uploading its Debug APK remains pending. In the current source, open Review service settings → Connect to DeepSeek on this phone to enter a personal API key, encrypted locally with Android Keystore. The self-hosted service option still keeps provider credentials on your server. Do not embed personal keys in APKs or source code. See [CURRENT.md](../CURRENT.md) for build inputs and validation evidence.

中文：OPC 构建渠道已实现，当前使用 Debug 证书签名，运行配置继承 release；因此是测试签名预览包，不等同于 Gradle 的 debug 版本。公开版与 OPC 的应用 ID 不同。正式发行前仍需固定专用签名和并存、覆盖升级验收。Release 应如实记录实际构建和验证结果。

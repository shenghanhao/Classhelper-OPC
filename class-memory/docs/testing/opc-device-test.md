# OPC 本机设备测试包

仅使用 `-PopcDeviceTest=true -PopcDeviceCaFile=<本机公开 CA 证书路径>` 开启可调试测试包。该包使用独立 OPC 包名和调试签名，显示设备测试名称；仅 localhost 和 127.0.0.1 的 HTTPS 信任额外 CA，其他域名仍使用系统信任。不得提供 CA 私钥或把测试包用于分发。未启用参数时 OPC 和 release 的网络信任不变。

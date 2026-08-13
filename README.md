# WattScope

Android 高通 SoC 功耗遥测监控工具（需 root）。基于 **Qualcomm Power Telemetry（QPT）**，配合附加监控项，以极低的运行时开销实时查看并记录功耗、频率、温度、FPS 等数据。

适用于已 root 的高通设备（KernelSU / Magisk），已在 Redmi K90 Pro Max（myron，Android 16）上验证。

> **注意**：当前 release APK 使用与源码仓库同目录的 `release.keystore` 签名（未随仓库分发）。如需自行构建可安装的版本，请参阅下方「从源码构建」。

## 功能

- **QPT 功耗遥测**：读取 `/sys/class/powercap/qpt` 的 8 个 zone（soc / cpu-m / cpu-l / gpu / nsp / debug-0/2/4），实时功率 + 能量
- **附加监控项**（每项一张独立图表）：
  - CPU 簇频率（动态枚举 cpufreq policy，如 CPU0-5 / CPU6-7）
  - 每核 CPU 占用（按簇着色，纵轴每 10% 分度）
  - 整机功耗（power_now）
  - CPU / DDR / GPU / **电池** 温度
  - GPU 频率 & 占用（双轴图）
  - DDR 频率
  - FPS（跟随前台应用，动态钳制到屏幕刷新率）
- **悬浮球记录**：点击开始记录当前应用，**退出该应用自动停止**；记录名称带应用名与图标
- **OSD 悬浮窗**：可选指标、竖排显示、可拖动、不记录；刷新缓慢的指标保持上次值继续显示
- **历史详情**：平均功耗置顶、全局同步十字光标 + 浮动 Tooltip、每个图表下方显示平均值、JSON/CSV 导出
- **低开销**：单次 su 往返批量采样、单事务落库、UI 节流——记录中单核 CPU 占用约 1.2%

## 下载

最新安装包见 [Releases](../../releases)。

## 要求

- Android 8.0+（O 以上悬浮窗 API）
- root（KernelSU 推荐 / Magisk），需授予 root 权限
- 悬浮窗 / 电池优化白名单权限
- QPT 支持可选：无 QPT 的设备也可记录频率、温度、FPS 等附加数据

## 从源码构建

```bash
# 需要 Android SDK；release 构建需要签名配置（app/build.gradle.kts 中从 keystore.properties 读取）
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名密钥（`keystore.properties` + `release.keystore`）不随仓库分发，请自行生成：

```
keytool -genkeypair -v -keystore release.keystore -alias release -keyalg RSA -keysize 2048 -validity 10000
```

## 技术栈

Kotlin · Jetpack Compose · Room（8 张表，v1→v4 迁移）· libsu（root shell）· 前台服务悬浮窗（WindowManager）· R8 混淆

## 许可

本项目仅供学习研究使用。

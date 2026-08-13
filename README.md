# WattScope

Android 高通 SoC 功耗遥测监控工具（需 root）。基于 **Qualcomm Power Telemetry（QPT）**，配合附加监控项，以极低的运行时开销实时查看并记录功耗、频率、温度、FPS 等数据。

适用于已 root 的高通设备（KernelSU / Magisk），已在 Redmi K90 Pro Max（myron，Android 16）上验证。

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


## 下载

最新安装包见 [Releases](../../releases)。

## 要求
- SM8850设备
- Android 8.0+（O 以上悬浮窗 API）
- root（KernelSU 推荐 / Magisk），需授予 root 权限
- 悬浮窗 / 电池优化白名单权限
- QPT 支持可选：无 QPT 的设备也可记录频率、温度、FPS 等附加数据


## 许可

本项目仅供学习研究使用。

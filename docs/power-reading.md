# WattScope 功耗读取方法详解

本文档详细说明 WattScope 如何读取各部分功耗数据：数据来源（sysfs 节点）、读取命令、解析与计算逻辑、单位换算，以及实测数据示例。所有内容均对应仓库源码实现，并在 Redmi K90 Pro Max（myron，Android 16，KernelSU root）上实测验证。

## 总览

| 监控项 | 数据来源 | 单位（节点原始值） | 实现代码 |
|---|---|---|---|
| SOC 总功耗（8 个 zone） | `/sys/class/powercap/qpt`（QPT 框架） | energy_uj（µJ）/ power_uw（µW） | `core/qpt/QptPowerReader.kt` |
| 整机功耗 | `/sys/class/power_supply/battery/power_now`（或 current×voltage 回退） | µW | `core/auxiliary/AuxReader.kt` |

所有读取均通过 libsu 以 root 身份执行（节点属主为 root，普通 shell 无读权限），并把多个 `cat` 合并成**一次 su 往返**以降低采样开销。

---

## 一、QPT 功耗遥测（各部分功耗）

### 1.1 什么是 QPT

Qualcomm Power Telemetry 是高通平台内核暴露的电源计量框架，挂载在 Linux 标准 **powercap** class 下：

```
/sys/class/powercap/qpt/
```

它以树形结构组织：根节点 `qpt:0` 是整机 SoC 聚合（name 为 `soc`），其子目录 `qpt:0:N` 是各功能域（rail）的计量单元。每个节点提供：

| 文件 | 含义 | 单位 |
|---|---|---|
| `name` | zone 名称（如 cpu-m） | 文本 |
| `energy_uj` | **累计消耗能量**（自启动以来单调递增的计数器） | µJ（微焦） |
| `power_uw` | 当前瞬时功率 | µW（微瓦） |
| `enabled` | QPT 总开关（根目录下） | 0/1 |
| `constraint_0_*` | 功率约束（本设备为 Dummy，无实际限制） | µW / µs |

### 1.2 实测目录结构（K90 Pro Max）

```
/sys/class/powercap/qpt/
├── enabled                  ← 总开关（写 1 开启，写 0 关闭）
└── qpt:0/                   ← 根 zone，name = "soc"（整机聚合）
    ├── energy_uj / power_uw
    ├── qpt:0:0/  name = cpu-m     ← 中核簇（Cortex-A7xx）
    ├── qpt:0:1/  name = cpu-l     ← 小核簇（Cortex-A5xx）
    ├── qpt:0:2/  name = gpu       ← GPU
    ├── qpt:0:3/  name = nsp       ← NPU（Hexagon，无负载时常为 0）
    ├── qpt:0:4/  name = debug-0   ← 厂商未命名的附加 rail
    ├── qpt:0:5/  name = debug-2   ← 厂商未命名的附加 rail（负载高，常随大核/DDR 活动）
    └── qpt:0:6/  name = debug-4   ← 厂商未命名的附加 rail
```

> zone 列表**完全动态发现**，不硬编码：不同 SoC 的 zone 数量与命名可以不同。`debug-*` 是高通未映射到具体名称的计量 rail，能量计算方法与其他 zone 完全一致。

实测验证：`soc` 的 energy_uj ≈ 所有子 zone 之和（误差来自各文件读取时刻的微小错位）。

### 1.3 开关与可用性检测

```bash
# 是否支持 QPT（目录存在）
[ -d '/sys/class/powercap/qpt/' ] && echo 1 || echo 0

# 是否已开启
cat /sys/class/powercap/qpt/enabled        # 输出 1 或 0

# 开启 / 关闭（root）
echo 1 > /sys/class/powercap/qpt/enabled
echo 0 > /sys/class/powercap/qpt/enabled
```

对应代码：`QptPowerReader.isAvailable()` / `isEnabled()` / `setEnabled()`。

### 1.4 zone 动态发现（scanZones）

用 `find` 递归查找所有含 `energy_uj` 的目录，再批量读取每个目录的 `name` 与 `power_uw` 可用性：

```bash
# 第一步：找出全部 zone 路径
find '/sys/class/powercap/qpt/' -mindepth 2 -type f -name energy_uj \
  | sed 's#/energy_uj$##' | sort

# 第二步：单条命令批量读 name + power_uw 是否存在（避免逐 zone 一次 su）
n=$(cat '<zone>/name' 2>/dev/null); [ -f '<zone>/power_uw' ] && pw=1 || pw=0; echo "<zone>|$n|$pw"
```

输出形如：

```
/sys/class/powercap/qpt/qpt:0/qpt:0:0|cpu-m|1
/sys/class/powercap/qpt/qpt:0/qpt:0:1|cpu-l|1
...
```

### 1.5 批量采样命令（buildSampleCommand）

每个采样 tick，把全部 zone 的读取合并为一条 shell 命令（与附加监控命令再合并，**整个 App 每个采样周期只有一次 su 往返**）：

```bash
for z in '<zone1>' '<zone2>' ...; do
  e=$(cat "$z/energy_uj" 2>/dev/null)
  p=$(cat "$z/power_uw"  2>/dev/null)
  echo "QPT|$z|$p|$e"
done
```

输出（打标签行，供解析器按前缀分发）：

```
QPT|/sys/class/powercap/qpt/qpt:0/qpt:0:0|142080|72480419968
QPT|/sys/class/powercap/qpt/qpt:0/qpt:0:1|1408|29490011008
...
```

### 1.6 解析与功耗计算（parseSampleLines）

每行按 `|` 拆分为 `QPT|路径|power_uw|energy_uj`，计算逻辑：

1. **优先使用 `power_uw`**（瞬时功率，硬件直接给出）——非空即作为该采样点的功率；
2. **回退计算**：若 `power_uw` 缺失（节点不存在或读取失败）且上一 tick 有能量记录，则用**能量差分法**求平均功率：

```
power_uw = (energy_uj_now − energy_uj_prev) × 1000 ÷ Δt_ms
```

   单位推导：µJ ÷ ms = µJ/ms = W×10⁻⁶ = µW，即 `deltaEnergy * 1_000 / deltaMs`；对计数器回绕（重启清零）取 `max(0, ΔE)` 保护。

3. `energy_uj` 原样入库（用于总能量统计与导出）。

### 1.7 实测数据示例（采样间隔 2s）

| zone | energy_uj (t0) | power_uw (t0) | power_uw (t0+2s) |
|---|---|---|---|
| cpu-m | 72,480,419,968 | 142,080（142 mW） | 141,568 |
| cpu-l | 29,490,011,008 | 1,408（1.4 mW） | 320 |
| gpu | 6,868,295,168 | 1,792（1.8 mW） | 704 |
| nsp | 4,939,520 | 0（NPU 空闲） | 0 |
| debug-0 | 9,216,268,224 | 25,472（25 mW） | 24,832 |
| debug-2 | 39,771,145,920 | 131,520（132 mW） | 124,928 |
| debug-4 | 6,168,053,120 | 15,488（15 mW） | 15,808 |

游戏负载下 cpu-m 可达 1 W 以上（历史记录 AVG 1.02 W）；`nsp` 仅在 NPU 推理时非零。

> QPT 关闭时（enabled=0），`power_uw` 恒为 0、`energy_uj` 冻结，此时采样无意义——App 会在关闭状态下停止采样并在 UI 提示。

---

## 二、整机功耗（电池输出功率）

SoC 各 rail 之和不含屏幕、射频、 PMIC 损耗等，整机功耗从**电池侧**计量：

### 2.1 首选：power_now

```bash
cat /sys/class/power_supply/battery/power_now     # 单位 µW
```

### 2.2 回退：current_now × voltage_now

部分内核不提供 `power_now`（实测 K90 Pro Max 该文件为空），此时用欧姆定律计算：

```bash
c=$(cat /sys/class/power_supply/battery/current_now)    # µA
v=$(cat /sys/class/power_supply/battery/voltage_now)    # µV
echo "$c $v" | awk '{p=$1*$2/1000000; if (p<0) p=-p; printf "BAT|%d\n", p}'
```

要点：

- **单位换算**：µA × µV = pW，÷ 1e6 得 µW；
- **必须用 awk 计算**：mksh 的算术是 32 位有符号，`100000 × 4400000` 会溢出，awk 是双精度浮点无此问题；
- **充电时电流为负**（充电方向 vs 放电方向定义），取绝对值表示功率大小——数据语义是"负载强度"而非"净耗电"；
- 可用性探测：`power_now` 存在即可用，否则要求 `current_now` 与 `voltage_now` 同时存在。

### 2.3 实测示例

```
current_now  = 1000        （µA，电池 Full 时极小）
voltage_now  = 4447000     （µV，≈4.45 V）
计算功率      = 1000 × 4447000 / 1e6 = 4,447 µW ≈ 4.4 mW
```

游戏负载下整机功耗实测 AVG ≈ 4.85 W，与 QPT 各 rail 之和（SOC 2.46 W + debug rails 等）趋势一致。

### 2.4 批量采样整合

整机功耗命令同样打标签（`BAT|µW`）并入每个采样周期的那一次 su 往返（`AuxReader.buildAuxCommand()`），与 QPT、频率、温度、每核占用等**同 tick 采集**，保证各图表时间轴对齐。

---

## 三、采样调度与开销控制

| 策略 | 做法 |
|---|---|
| 单次 su 往返 | QPT + 附加监控（频率/温度/整机功耗/每核占用/GPU/DDR）合并为一条 shell 命令，每 tick 仅一次 fork + 数据吞吐 |
| 事务落库 | Room `withTransaction`，8 张表一次 WAL 提交 |
| UI 节流 | StateFlow 每 500 ms 合并更新一次，采样间隔与刷新频率解耦 |
| 采样间隔 | 100/200/500/1000 ms 可选，持久化记忆 |

实测整机 App 进程记录中单核 CPU 占用约 **1.2%**（`/proc/<pid>/stat` jiffies 差值法），空闲预览约 1.1%。

## 四、注意事项与常见问题

- **root 必须**：`/sys/class/powercap/qpt/*` 与部分 battery 节点属主 root，普通 adb shell 读取会被拒绝；
- **QPT 开关**：采样前确认 `enabled=1`，否则读数为 0；
- **energy_uj 是累计值**：只在需要"一段时间内总能量"时做差分，跨重启/回绕不可比；
- **debug-\* zone**：厂商未命名的 rail，数值真实有效但物理归属未知，App 原样展示；
- **nsp 为 0**：NPU 无负载时的正常现象，非读取失败；
- **power_uw 抖动**：瞬时值波动大，看趋势请用图表平均值或能量差分；
- **device power 与 SOC 的差值**：包含屏幕、蜂窝/Wi-Fi 射频、内存（部分 rail）、PMIC 转换损耗等 SoC 计量之外的整机开销。

package com.qpt.powermonitor.core.auxiliary

import com.qpt.powermonitor.core.root.RootManager
import com.qpt.powermonitor.domain.ClusterFreqSample
import com.qpt.powermonitor.domain.CoreUsageSample
import com.qpt.powermonitor.domain.CpuCluster
import com.qpt.powermonitor.domain.DdrFreqSample
import com.qpt.powermonitor.domain.GpuSample
import com.qpt.powermonitor.domain.GpuSensor
import com.qpt.powermonitor.domain.TempSample
import com.qpt.powermonitor.domain.TempSensors

/**
 * 附加监控项采集器：CPU 簇频率、每核占用、整机功耗、CPU/DDR/GPU 温度、GPU 频率/占用、DDR 频率。
 * 全部动态枚举 sysfs 节点（不硬编码路径，无节点则对应项自动缺失）。
 * 采样命令由 [buildAuxCommand] 构建（可与 QPT 命令合并为单次 su 往返），结果由 [parseAuxLines] 解析。
 */
class AuxReader(private val rootManager: RootManager) {

    // /proc/stat 每核 (total, idle) 差值基准
    private val prevStat = mutableMapOf<Int, Pair<Long, Long>>()
    // FPS 采样基准（dumpsys gfxinfo 累计帧数差值）
    private var prevFrames = 0L
    private var prevFpsTimeMs = 0L
    private var fpsPackage: String? = null
    // 屏幕刷新率（FPS 上限，懒加载一次）
    private var screenMaxFps: Float? = null

    data class AuxBatch(
        val clusterFreqs: List<ClusterFreqSample> = emptyList(),
        val temps: List<TempSample> = emptyList(),
        val devicePowerUw: Long? = null,
        val coreUsages: List<CoreUsageSample> = emptyList(),
        val gpuFreqHz: Long? = null,
        val gpuUsagePercent: Float? = null,
        val ddrFreqHz: Long? = null,
    )

    suspend fun scanClusters(): List<CpuCluster> {
        val lines = rootManager.execute(
            "for p in /sys/devices/system/cpu/cpufreq/policy*; do " +
                "[ -f \"\$p/scaling_cur_freq\" ] || continue; " +
                "c=\$(cat \"\$p/related_cpus\" 2>/dev/null); " +
                "echo \"\$p|\$c\"; done",
        )
        return lines.mapNotNull { line ->
            val idx = line.lastIndexOf('|')
            if (idx <= 0) return@mapNotNull null
            val path = line.substring(0, idx).trim()
            val cores = line.substring(idx + 1).trim()
            if (cores.isBlank()) return@mapNotNull null
            CpuCluster(path = path, name = "CPU$cores")
        }
    }

    suspend fun scanTempZones(): TempSensors {
        val lines = rootManager.execute(
            "for z in /sys/class/thermal/thermal_zone*; do " +
                "t=\$(cat \"\$z/type\" 2>/dev/null); " +
                "[ -n \"\$t\" ] && echo \"\$z|\$t\"; done",
        )
        val cpuZones = mutableListOf<String>()
        var ddrZone: String? = null
        var gpuZone: String? = null
        lines.forEach { line ->
            val idx = line.lastIndexOf('|')
            if (idx <= 0) return@forEach
            val path = line.substring(0, idx).trim()
            val type = line.substring(idx + 1).trim().lowercase()
            when {
                "ddr" in type || "dram" in type -> if (ddrZone == null) ddrZone = path
                "gpu" in type -> if (gpuZone == null) gpuZone = path
                // 排除硬件跳闸阈值传感器（如 cpu-hw-trip-*，恒值 105°C，非真实温度）
                "cpu" in type && "trip" !in type -> cpuZones += path
            }
        }
        // 电池温度：标准 Android 电源节点（0.1°C 单位），独立于 thermal zone
        val hasBatteryTemp = rootManager.execute(
            "[ -f /sys/class/power_supply/battery/temp ] && echo 1 || echo 0",
        ).firstOrNull()?.trim() == "1"
        return TempSensors(cpuZones = cpuZones.sorted(), ddrZone = ddrZone, gpuZone = gpuZone, hasBatteryTemp = hasBatteryTemp)
    }

    /** GPU 传感器：gpuclk 频率节点 + gpubusy(busy/total) 或 gpu_busy_percentage(百分比) 占用节点。 */
    suspend fun scanGpuSensor(): GpuSensor? {
        val root = "/sys/class/kgsl/kgsl-3d0"
        val freqOk = rootManager.execute(
            "[ -f '$root/gpuclk' ] && echo 1 || echo 0",
        ).firstOrNull()?.trim() == "1"
        if (!freqOk) return null
        val busyIsPercent = rootManager.execute(
            "[ -f '$root/gpubusy' ] && echo 0 || { [ -f '$root/gpu_busy_percentage' ] && echo 1 || echo 2; }",
        ).firstOrNull()?.trim()
        val busyPath = when (busyIsPercent) {
            "0" -> "$root/gpubusy"
            "1" -> "$root/gpu_busy_percentage"
            else -> null
        }
        return GpuSensor(freqPath = "$root/gpuclk", busyPath = busyPath, busyIsPercent = busyIsPercent == "1")
    }

    /** DDR 频率：动态发现 devfreq 中名称含 ddr/memory/bw/llcc 且可读 cur_freq 的节点；无则返回 null。 */
    suspend fun scanDdrFreqPath(): String? {
        val lines = rootManager.execute(
            "for d in /sys/class/devfreq/*/; do " +
                "n=\$(basename \"\$d\"); " +
                "case \"\$n\" in *ddr*|*memory*|*memlat*|*llcc*|*bw*) [ -f \"\$d/cur_freq\" ] && echo \"\$d\";; esac; done",
        )
        return lines.firstOrNull { it.isNotBlank() }?.trim()
    }

    /**
     * FPS 采样：dumpsys gfxinfo 累计帧数差值 / 时间差。
     * 单次 su 往返内先解析 topResumedActivity 前台包名，再 dump 该包 gfxinfo 累计帧数，
     * 避免调用方额外查询前台包名（两次 dumpsys 合并为一次）。
     * 应用切换时重置基线；无法读取或静置无新帧时返回 null。
     */
    suspend fun sampleFps(): Float? {
        val now = System.currentTimeMillis()
        val out = rootManager.execute(
            // 1) 解析前台包名；2) 打标签输出；3) 单 awk 提取累计帧数
            "pkg=\$(dumpsys activity activities 2>/dev/null | grep -m1 'topResumedActivity=' | " +
                "sed -E 's/.*u[0-9]+ ([^/ ]+)\\/.*/\\1/'); " +
                "echo \"FPKG|\$pkg\"; " +
                "dumpsys gfxinfo \"\$pkg\" 2>/dev/null | awk '/Total frames rendered/{print \"FFRM|\"\$NF; exit}'",
        )
        var packageName: String? = null
        var frames: Long? = null
        out.forEach { line ->
            when {
                line.startsWith("FPKG|") -> packageName = line.substringAfter('|').trim()
                    .takeIf { it.startsWith("com.") || it.startsWith("android.") }
                line.startsWith("FFRM|") -> frames = line.substringAfter('|').trim().toLongOrNull()
            }
        }
        val pkg = packageName ?: return null
        val frameCount = frames ?: run {
            fpsPackage = null
            return null
        }
        if (fpsPackage != pkg) {
            fpsPackage = pkg
            prevFrames = frameCount
            prevFpsTimeMs = now
            return null
        }
        if (prevFrames > 0L && now > prevFpsTimeMs) {
            val dtSec = (now - prevFpsTimeMs) / 1000f
            if (dtSec >= 0.5f) {
                val delta = frameCount - prevFrames
                prevFrames = frameCount
                prevFpsTimeMs = now
                // 静止页面无新帧（delta<=0）时不产出采样点，避免 FPS 图出现误导性 0
                if (delta <= 0) return null
                // 上限取真实屏幕刷新率，避免瞬时毛刺被硬编码上限误钳到 144
                return ((delta).toFloat() / dtSec).coerceIn(0f, screenMaxFps())
            }
        }
        prevFrames = frameCount
        prevFpsTimeMs = now
        return null
    }

    /** 读取屏幕最大支持刷新率（Hz）作为 FPS 上限，失败回退 120。懒加载一次。 */
    private suspend fun screenMaxFps(): Float {
        screenMaxFps?.let { return it }
        // 设备输出形如 fps=120.00001 / supportedRefreshRates [120.00001, 90.0, 60.0]
        // 取所有 fps= 值中的最大者 = 屏幕最大支持帧率（高帧游戏可到 120/144）
        val out = rootManager.execute(
            "dumpsys display 2>/dev/null | grep -oE 'fps=[0-9]+([.][0-9]+)?' | grep -oE '[0-9]+([.][0-9]+)?' | sort -rn | head -1",
        )
        val rate = out.firstOrNull()?.trim()?.toFloatOrNull()?.takeIf { it in 1f..300f } ?: 120f
        screenMaxFps = rate
        return rate
    }

    /** 整机功耗可用性（存在 power_now，或 current_now + voltage_now 均存在）。 */
    suspend fun isBatteryPowerAvailable(): Boolean =
        rootManager.execute(
            "[ -f /sys/class/power_supply/battery/power_now ] && echo 1 || " +
                "{ [ -f /sys/class/power_supply/battery/current_now ] && [ -f /sys/class/power_supply/battery/voltage_now ] && echo 1 || echo 0; }",
        ).firstOrNull()?.trim() == "1"

    /**
     * 构建全部附加监控项的批量采样命令（打标签输出，可与 QPT 命令合并为一次 su 往返）。
     * 输出行：FREQ|簇名|kHz / TEMP|类别|毫摄氏度 / BAT|µW / GPUF|Hz / GPUB|busy|total / GPUBP|% / DDR|Hz / STAT|cpuN|...
     */
    fun buildAuxCommand(
        clusters: List<CpuCluster>,
        sensors: TempSensors,
        gpu: GpuSensor?,
        ddrFreqPath: String?,
    ): String {
        val commands = mutableListOf<String>()
        if (clusters.isNotEmpty()) {
            commands += clusters.joinToString("; ") {
                "echo \"FREQ|${it.name}|\$(cat '${it.path}/scaling_cur_freq' 2>/dev/null)\""
            }
        }
        sensors.cpuZones.forEach { commands += "echo \"TEMP|cpu|\$(cat '$it/temp' 2>/dev/null)\"" }
        sensors.ddrZone?.let { commands += "echo \"TEMP|ddr|\$(cat '$it/temp' 2>/dev/null)\"" }
        sensors.gpuZone?.let { commands += "echo \"TEMP|gpu|\$(cat '$it/temp' 2>/dev/null)\"" }
        if (sensors.hasBatteryTemp) commands += "echo \"TEMP|batt|\$(cat /sys/class/power_supply/battery/temp 2>/dev/null)\""
        commands += batteryCommand()
        gpu?.let {
            commands += "echo \"GPUF|\$(cat '${it.freqPath}' 2>/dev/null)\""
            if (it.busyPath != null) {
                if (it.busyIsPercent) {
                    commands += "echo \"GPUBP|\$(cat '${it.busyPath}' 2>/dev/null)\""
                } else {
                    commands += "echo \"GPUB|\$(cat '${it.busyPath}' 2>/dev/null)\""
                }
            }
        }
        ddrFreqPath?.let { commands += "echo \"DDR|\$(cat '$it/cur_freq' 2>/dev/null)\"" }
        commands += "grep -E '^cpu[0-9]+ ' /proc/stat | sed -E 's/^cpu([0-9]+) /STAT|cpu\\1|/; s/ /|/g'"
        return commands.joinToString("; ")
    }

    fun parseAuxLines(lines: List<String>, timestampMs: Long): AuxBatch {
        val freqs = mutableListOf<ClusterFreqSample>()
        val cpuTemps = mutableListOf<Long>()
        var ddrTemp: Long? = null
        var gpuTemp: Long? = null
        var battTemp: Long? = null
        var devicePower: Long? = null
        var gpuFreqHz: Long? = null
        var gpuUsagePercent: Float? = null
        var ddrFreqHz: Long? = null
        val statLines = mutableListOf<String>()
        lines.forEach { line ->
            when {
                line.startsWith("FREQ|") -> {
                    val parts = line.split('|')
                    if (parts.size >= 3) {
                        parts[2].trim().toLongOrNull()?.let { freqs += ClusterFreqSample(timestampMs, parts[1], it) }
                    }
                }
                line.startsWith("TEMP|") -> {
                    val parts = line.split('|')
                    if (parts.size >= 3) {
                        val temp = parts[2].trim().toLongOrNull()
                        if (temp != null) {
                            when (parts[1]) {
                                "cpu" -> cpuTemps += temp
                                "ddr" -> ddrTemp = temp
                                "gpu" -> gpuTemp = temp
                                "batt" -> battTemp = temp
                            }
                        }
                    }
                }
                line.startsWith("BAT|") -> {
                    devicePower = line.substringAfter('|').trim().toLongOrNull()?.takeIf { it >= 0 }
                }
                line.startsWith("GPUF|") -> {
                    gpuFreqHz = line.substringAfter('|').trim().toLongOrNull()
                }
                line.startsWith("GPUB|") -> {
                    val parts = line.substringAfter('|').trim().split(' ')
                    val busy = parts.getOrNull(0)?.trim()?.toLongOrNull()
                    val total = parts.getOrNull(1)?.trim()?.toLongOrNull()
                    if (busy != null && total != null && total > 0) {
                        gpuUsagePercent = (busy.toFloat() / total * 100f).coerceIn(0f, 100f)
                    }
                }
                line.startsWith("GPUBP|") -> {
                    val value = line.substringAfter('|').trim().removeSuffix("%").trim().toFloatOrNull()
                    if (value != null) gpuUsagePercent = value.coerceIn(0f, 100f)
                }
                line.startsWith("DDR|") -> {
                    ddrFreqHz = line.substringAfter('|').trim().toLongOrNull()
                }
                line.startsWith("STAT|") -> statLines += line
            }
        }
        val temps = mutableListOf<TempSample>()
        // CPU 温度：排除异常恒值（如跳闸阈值 >=110°C），避免取到非真实传感器
        cpuTemps.filter { it < 110_000 }.maxOrNull()?.let { temps += TempSample(timestampMs, "cpu", it / 1000f) }
        ddrTemp?.let { temps += TempSample(timestampMs, "ddr", it / 1000f) }
        gpuTemp?.let { temps += TempSample(timestampMs, "gpu", it / 1000f) }
        // 电池温度：power_supply 节点单位为 0.1°C，过滤异常值（-10 ~ 100°C）
        battTemp?.takeIf { it in -100..1000 }?.let { temps += TempSample(timestampMs, "batt", it / 10f) }
        return AuxBatch(
            clusterFreqs = freqs,
            temps = temps,
            devicePowerUw = devicePower,
            coreUsages = parseCoreUsages(statLines, timestampMs),
            gpuFreqHz = gpuFreqHz,
            gpuUsagePercent = gpuUsagePercent,
            ddrFreqHz = ddrFreqHz,
        )
    }

    private fun batteryCommand(): String =
        "if [ -f /sys/class/power_supply/battery/power_now ]; then " +
            "echo \"BAT|\$(cat /sys/class/power_supply/battery/power_now)\"; " +
            "elif [ -f /sys/class/power_supply/battery/current_now ] && [ -f /sys/class/power_supply/battery/voltage_now ]; then " +
            "c=\$(cat /sys/class/power_supply/battery/current_now); v=\$(cat /sys/class/power_supply/battery/voltage_now); " +
            // awk 计算避免 mksh 32 位溢出；充电时电流为负，取绝对值表示功率大小
            "echo \"\$c \$v\" | awk '{p=\$1*\$2/1000000; if (p<0) p=-p; printf \"BAT|%d\\n\", p}'; fi"

    private fun parseCoreUsages(statLines: List<String>, timestampMs: Long): List<CoreUsageSample> {
        val now = mutableMapOf<Int, Pair<Long, Long>>()
        statLines.forEach { line ->
            val parts = line.split('|')
            if (parts.size < 6) return@forEach
            val core = parts[1].removePrefix("cpu").toIntOrNull() ?: return@forEach
            val values = parts.drop(2).mapNotNull { it.trim().toLongOrNull() }
            if (values.size < 4) return@forEach
            val total = values.sum()
            val idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L } // idle + iowait
            now[core] = total to idle
        }
        val result = prevStat.mapNotNull { (core, prev) ->
            val cur = now[core] ?: return@mapNotNull null
            val deltaTotal = cur.first - prev.first
            val deltaIdle = cur.second - prev.second
            val usage = if (deltaTotal > 0) {
                ((deltaTotal - deltaIdle).toFloat() / deltaTotal * 100f).coerceIn(0f, 100f)
            } else {
                0f
            }
            CoreUsageSample(timestampMs, core, usage)
        }
        prevStat.clear()
        prevStat.putAll(now)
        return result.sortedBy { it.coreIndex }
    }

    fun resetDeltas() {
        prevStat.clear()
        prevFrames = 0L
        prevFpsTimeMs = 0L
        fpsPackage = null
    }
}

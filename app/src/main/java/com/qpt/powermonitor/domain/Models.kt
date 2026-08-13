package com.qpt.powermonitor.domain

data class PowerZone(
    val path: String,
    val name: String,
    val hasPowerNode: Boolean,
)

data class ZoneSample(
    val zonePath: String,
    val zoneName: String,
    val timestampMs: Long,
    val energyUj: Long,
    val powerUw: Long?,
    val computedPowerUw: Long?,
) {
    val effectivePowerUw: Long get() = powerUw ?: computedPowerUw ?: 0L
}

data class ZoneStats(
    val zoneName: String,
    val maxPowerUw: Long,
    val minPowerUw: Long,
    val avgPowerUw: Long,
    val totalEnergyUj: Long,
    val peakAtMs: Long,
)

enum class SamplingInterval(val millis: Long, val label: String) {
    Ms100(100, "100 ms"),
    Ms200(200, "200 ms"),
    Ms500(500, "500 ms"),
    Ms1000(1_000, "1000 ms"),
}

data class RecordingState(
    val isRecording: Boolean = false,
    val qptAvailable: Boolean = false,
    val qptEnabled: Boolean = false,
    val status: String = "正在检查 QPT",
    val zones: List<PowerZone> = emptyList(),
    val latestSamples: List<ZoneSample> = emptyList(),
    val interval: SamplingInterval = SamplingInterval.Ms200,
    val clusters: List<CpuCluster> = emptyList(),
    val tempSensors: TempSensors = TempSensors(),
    val devicePowerAvailable: Boolean = false,
    val latestDevicePowerUw: Long? = null,
    val gpuSensor: GpuSensor? = null,
    val ddrFreqPath: String? = null,
    val latestFps: Float? = null,
) {
    /** 能否开始记录：QPT 可用已启用，或存在任意附加监控源（无 QPT 的设备仅记录 freq/温度等）。 */
    val canRecord: Boolean
        get() = (qptAvailable && qptEnabled) ||
            clusters.isNotEmpty() ||
            !tempSensors.isEmpty ||
            devicePowerAvailable ||
            gpuSensor != null ||
            ddrFreqPath != null
}

data class CpuCluster(
    val path: String,
    val name: String,
)

data class GpuSensor(
    val freqPath: String,
    val busyPath: String?,
    val busyIsPercent: Boolean,
)

data class TempSensors(
    val cpuZones: List<String> = emptyList(),
    val ddrZone: String? = null,
    val gpuZone: String? = null,
    val hasBatteryTemp: Boolean = false,
) {
    val isEmpty: Boolean get() = cpuZones.isEmpty() && ddrZone == null && gpuZone == null && !hasBatteryTemp
}

data class ClusterFreqSample(
    val timestampMs: Long,
    val clusterName: String,
    val freqKhz: Long,
)

data class CoreUsageSample(
    val timestampMs: Long,
    val coreIndex: Int,
    val usagePercent: Float,
)

data class TempSample(
    val timestampMs: Long,
    val sensor: String,
    val tempC: Float,
)

data class DevicePowerSample(
    val timestampMs: Long,
    val powerUw: Long,
)

data class GpuSample(
    val timestampMs: Long,
    val freqHz: Long,
    val usagePercent: Float?,
)

data class DdrFreqSample(
    val timestampMs: Long,
    val freqHz: Long,
)

data class FpsSample(
    val timestampMs: Long,
    val fps: Float,
)

/** OSD 悬浮窗可选监控项（enum 声明顺序即默认显示顺序）。 */
enum class OsdMetric(val key: String, val label: String) {
    SOC_POWER("soc_power", "SOC"),
    CPU_L_POWER("cpu_l_power", "CPU-L"),
    CPU_M_POWER("cpu_m_power", "CPU-M"),
    GPU_POWER("gpu_power", "GPU"),
    GPU_FREQ("gpu_freq", "GPU频率"),
    FPS("fps", "FPS"),
    CPU_TEMP("cpu_temp", "CPU温度"),
    GPU_TEMP("gpu_temp", "GPU温度"),
    DDR_TEMP("ddr_temp", "DDR温度"),
    GPU_USAGE("gpu_usage", "GPU占用"),
    DEVICE_POWER("device_power", "整机"),
}

/** OSD 悬浮窗实时数据（不落库，仅内存展示）。 */
data class OsdSample(
    val socPowerUw: Long? = null,
    val cpuLPowerUw: Long? = null,
    val cpuMPowerUw: Long? = null,
    val gpuPowerUw: Long? = null,
    val gpuFreqHz: Long? = null,
    val gpuUsagePercent: Float? = null,
    val fps: Float? = null,
    val cpuTempC: Float? = null,
    val gpuTempC: Float? = null,
    val ddrTempC: Float? = null,
    val devicePowerUw: Long? = null,
) {
    /** 将指定监控项格式化为 "标签 值单位" 展示文本；无数据返回 null。 */
    fun format(metric: OsdMetric): String? = when (metric) {
        OsdMetric.SOC_POWER -> socPowerUw?.let { "SOC ${uwToW(it)}" }
        OsdMetric.CPU_L_POWER -> cpuLPowerUw?.let { "CPU-L ${uwToW(it)}" }
        OsdMetric.CPU_M_POWER -> cpuMPowerUw?.let { "CPU-M ${uwToW(it)}" }
        OsdMetric.GPU_POWER -> gpuPowerUw?.let { "GPU ${uwToW(it)}" }
        OsdMetric.GPU_FREQ -> gpuFreqHz?.let { "GPU ${freqToStr(it)}" }
        OsdMetric.FPS -> fps?.let { "FPS ${"%.0f".format(it)}" }
        OsdMetric.CPU_TEMP -> cpuTempC?.let { "CPU ${"%.0f".format(it)}℃" }
        OsdMetric.GPU_TEMP -> gpuTempC?.let { "GPU ${"%.0f".format(it)}℃" }
        OsdMetric.DDR_TEMP -> ddrTempC?.let { "DDR ${"%.0f".format(it)}℃" }
        OsdMetric.GPU_USAGE -> gpuUsagePercent?.let { "GPU ${"%.0f".format(it)}%" }
        OsdMetric.DEVICE_POWER -> devicePowerUw?.let { "整机 ${uwToW(it)}" }
    }

    private fun uwToW(uw: Long): String = "%.2fW".format(uw / 1_000_000.0)
    private fun freqToStr(hz: Long): String =
        if (hz >= 1_000_000_000) "%.2fGHz".format(hz / 1_000_000_000.0)
        else if (hz >= 1_000_000) "%.0fMHz".format(hz / 1_000_000.0)
        else "%.0fkHz".format(hz / 1_000.0)
}

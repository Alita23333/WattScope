package com.qpt.powermonitor.ui

import com.qpt.powermonitor.data.local.ClusterFreqEntity
import com.qpt.powermonitor.data.local.CoreUsageEntity
import com.qpt.powermonitor.data.local.DdrFreqEntity
import com.qpt.powermonitor.data.local.DevicePowerEntity
import com.qpt.powermonitor.data.local.FpsEntity
import com.qpt.powermonitor.data.local.GpuEntity
import com.qpt.powermonitor.data.local.RecordEntity
import com.qpt.powermonitor.data.local.SampleEntity
import com.qpt.powermonitor.data.local.TempEntity
import com.qpt.powermonitor.domain.ZoneStats

data class DetailUiState(
    val record: RecordEntity? = null,
    val samples: List<SampleEntity> = emptyList(),
    val stats: List<ZoneStats> = emptyList(),
    val avgDevicePowerUw: Long = 0,
    val clusterAvgFreqs: List<Pair<String, Long>> = emptyList(),
    val coreAvgUsages: List<Pair<Int, Float>> = emptyList(),
    val sensorAvgTemps: List<Pair<String, Float>> = emptyList(),
    val avgGpuFreqHz: Long = 0,
    val avgGpuUsage: Float = 0f,
    val avgDdrFreqHz: Long = 0,
    val avgFps: Float = 0f,
    // 原始采样序列（用于折线图）
    val clusterFreqSamples: List<ClusterFreqEntity> = emptyList(),
    val coreUsageSamples: List<CoreUsageEntity> = emptyList(),
    val tempSamples: List<TempEntity> = emptyList(),
    val devicePowerSamples: List<DevicePowerEntity> = emptyList(),
    val gpuSamples: List<GpuEntity> = emptyList(),
    val ddrFreqSamples: List<DdrFreqEntity> = emptyList(),
    val fpsSamples: List<FpsEntity> = emptyList(),
) {
    val hasAuxData: Boolean
        get() = avgDevicePowerUw > 0 || clusterAvgFreqs.isNotEmpty() || coreAvgUsages.isNotEmpty() ||
            sensorAvgTemps.isNotEmpty() || avgGpuFreqHz > 0 || avgDdrFreqHz > 0

    val hasChartData: Boolean
        get() = clusterFreqSamples.isNotEmpty() || gpuSamples.isNotEmpty() || devicePowerSamples.isNotEmpty() ||
            tempSamples.isNotEmpty() || ddrFreqSamples.isNotEmpty() || coreUsageSamples.isNotEmpty() || fpsSamples.isNotEmpty()
}

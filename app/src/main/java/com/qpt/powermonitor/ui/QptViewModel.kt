package com.qpt.powermonitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qpt.powermonitor.data.local.ClusterFreqEntity
import com.qpt.powermonitor.data.local.CoreUsageEntity
import com.qpt.powermonitor.data.local.DdrFreqEntity
import com.qpt.powermonitor.data.local.DevicePowerEntity
import com.qpt.powermonitor.data.local.FpsEntity
import com.qpt.powermonitor.data.local.GpuEntity
import com.qpt.powermonitor.data.local.RecordEntity
import com.qpt.powermonitor.data.local.SampleEntity
import com.qpt.powermonitor.data.local.TempEntity
import com.qpt.powermonitor.data.repo.MonitorRepository
import com.qpt.powermonitor.domain.OsdMetric
import com.qpt.powermonitor.domain.SamplingInterval
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QptViewModel(private val repository: MonitorRepository) : ViewModel() {
    val state = repository.state
    val records: StateFlow<List<RecordEntity>> = repository.records.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 悬浮窗开关状态（Compose 可观察，写入即响应）。 */
    val bubbleEnabled: StateFlow<Boolean> = repository.bubbleEnabledFlow

    /** OSD 悬浮窗开关与显示项。 */
    val osdEnabled: StateFlow<Boolean> = repository.osdEnabledFlow
    val osdMetrics: StateFlow<List<OsdMetric>> = repository.osdMetricsFlow

    init {
        viewModelScope.launch { repository.refreshDeviceState() }
    }

    fun refresh() = viewModelScope.launch { repository.refreshDeviceState() }
    fun setEnabled(enabled: Boolean) = viewModelScope.launch { repository.setQptEnabled(enabled) }
    fun setInterval(interval: SamplingInterval) = repository.setInterval(interval)
    fun startRecording() = viewModelScope.launch { repository.startRecording() }
    fun stopRecording() = viewModelScope.launch { repository.stopRecording() }
    fun deleteRecord(id: Long) = viewModelScope.launch { repository.deleteRecord(id) }
    fun deleteAll() = viewModelScope.launch { repository.deleteAll() }
    fun setOsdEnabled(enabled: Boolean) { repository.osdEnabled = enabled }
    fun setOsdMetrics(metrics: List<OsdMetric>) = repository.setOsdMetrics(metrics)

    /** 生成完整导出内容（JSON / CSV），供 SAF 保存到用户选择的位置。 */
    suspend fun exportRecord(recordId: Long, asJson: Boolean): String =
        if (asJson) repository.exportRecordJson(recordId) else repository.exportRecordCsv(recordId)
    fun samples(recordId: Long): StateFlow<List<SampleEntity>> =
        repository.observeSamples(recordId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun stats(recordId: Long): StateFlow<DetailUiState> {
        val auxA = combine(
            repository.observeClusterFreqs(recordId),
            repository.observeCoreUsages(recordId),
            repository.observeTemps(recordId),
            repository.observeDevicePowers(recordId),
        ) { freqs, cores, temps, powers ->
            AuxDataA(freqs, cores, temps, powers)
        }
        val auxB = combine(
            repository.observeGpus(recordId),
            repository.observeDdrFreqs(recordId),
            repository.observeFps(recordId),
        ) { gpus, ddrFreqs, fpsList ->
            AuxDataB(gpus, ddrFreqs, fpsList)
        }
        return combine(repository.observeRecord(recordId), repository.observeSamples(recordId), auxA, auxB) { record, samples, a, b ->
            DetailUiState(
                record = record,
                samples = samples,
                stats = repository.stats(samples),
                avgDevicePowerUw = a.powers.map { it.powerUw }.average().toLong().takeIf { a.powers.isNotEmpty() } ?: 0L,
                clusterAvgFreqs = a.freqs.groupBy { it.clusterName }
                    .mapValues { (_, v) -> v.map { it.freqKhz }.average().toLong() }
                    .toList().sortedBy { it.first },
                coreAvgUsages = a.cores.groupBy { it.coreIndex }
                    .mapValues { (_, v) -> v.map { it.usagePercent }.average().toFloat() }
                    .toList().sortedBy { it.first },
                sensorAvgTemps = a.temps.groupBy { it.sensor }
                    .mapValues { (_, v) -> v.map { it.tempC }.average().toFloat() }
                    .toList(),
                avgGpuFreqHz = b.gpus.map { it.freqHz }.average().toLong().takeIf { b.gpus.isNotEmpty() } ?: 0L,
                avgGpuUsage = b.gpus.mapNotNull { it.usagePercent }.average().toFloat().takeIf { b.gpus.any { it.usagePercent != null } } ?: 0f,
                avgDdrFreqHz = b.ddrFreqs.map { it.freqHz }.average().toLong().takeIf { b.ddrFreqs.isNotEmpty() } ?: 0L,
                avgFps = b.fps.map { it.fps }.average().toFloat().takeIf { b.fps.isNotEmpty() } ?: 0f,
                clusterFreqSamples = a.freqs,
                coreUsageSamples = a.cores,
                tempSamples = a.temps,
                devicePowerSamples = a.powers,
                gpuSamples = b.gpus,
                ddrFreqSamples = b.ddrFreqs,
                fpsSamples = b.fps,
            )
        }
            // 录制进行时其他记录的写入会触发 Room 表级失效、Flow 重发射；
            // 数据未变化时不向下游发射，避免详情页无谓重组与图表手势抖动
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())
    }

    private data class AuxDataA(
        val freqs: List<ClusterFreqEntity> = emptyList(),
        val cores: List<CoreUsageEntity> = emptyList(),
        val temps: List<TempEntity> = emptyList(),
        val powers: List<DevicePowerEntity> = emptyList(),
    )

    private data class AuxDataB(
        val gpus: List<GpuEntity> = emptyList(),
        val ddrFreqs: List<DdrFreqEntity> = emptyList(),
        val fps: List<FpsEntity> = emptyList(),
    )
}

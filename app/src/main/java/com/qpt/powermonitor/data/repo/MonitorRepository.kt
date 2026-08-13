package com.qpt.powermonitor.data.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.room.withTransaction
import com.qpt.powermonitor.core.auxiliary.AuxReader
import com.qpt.powermonitor.core.qpt.QptPowerReader
import com.qpt.powermonitor.core.root.RootManager
import com.qpt.powermonitor.data.local.AuxSampleDao
import com.qpt.powermonitor.data.local.ClusterFreqEntity
import com.qpt.powermonitor.data.local.CoreUsageEntity
import com.qpt.powermonitor.data.local.DdrFreqEntity
import com.qpt.powermonitor.data.local.DevicePowerEntity
import com.qpt.powermonitor.data.local.FpsEntity
import com.qpt.powermonitor.data.local.GpuEntity
import com.qpt.powermonitor.data.local.QptDatabase
import com.qpt.powermonitor.data.local.RecordDao
import com.qpt.powermonitor.data.local.RecordEntity
import com.qpt.powermonitor.data.local.SampleDao
import com.qpt.powermonitor.data.local.SampleEntity
import com.qpt.powermonitor.data.local.TempEntity
import com.qpt.powermonitor.domain.ClusterFreqSample
import com.qpt.powermonitor.domain.CoreUsageSample
import com.qpt.powermonitor.domain.DdrFreqSample
import com.qpt.powermonitor.domain.GpuSample
import com.qpt.powermonitor.domain.OsdMetric
import com.qpt.powermonitor.domain.OsdSample
import com.qpt.powermonitor.domain.PowerZone
import com.qpt.powermonitor.domain.RecordingState
import com.qpt.powermonitor.domain.SamplingInterval
import com.qpt.powermonitor.domain.TempSample
import com.qpt.powermonitor.domain.ZoneSample
import com.qpt.powermonitor.domain.ZoneStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MonitorRepository(
    context: Context,
    private val database: QptDatabase,
    private val recordDao: RecordDao,
    private val sampleDao: SampleDao,
    private val auxSampleDao: AuxSampleDao,
    private val qptReader: QptPowerReader,
    private val auxReader: AuxReader,
    private val rootManager: RootManager,
) {
    private val appInspector = AppInspector(context, rootManager)
    private val prefs: SharedPreferences = context.getSharedPreferences("qpt_settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var samplerJob: Job? = null
    private var previewJob: Job? = null
    private var osdJob: Job? = null
    private var activeRecord: RecordEntity? = null

    private val _state = MutableStateFlow(RecordingState(interval = loadSavedInterval()))
    val state: StateFlow<RecordingState> = _state.asStateFlow()
    val records = recordDao.observeRecords()

    /** 读取持久化的采样间隔（默认 200ms）。 */
    private fun loadSavedInterval(): SamplingInterval =
        prefs.getString("sampling_interval", null)
            ?.let { name -> SamplingInterval.entries.firstOrNull { it.name == name } }
            ?: SamplingInterval.Ms200

    // 悬浮窗开关：Compose 可观察状态（写偏好同时更新 StateFlow，开关 UI 立即响应）
    private val _bubbleEnabled = MutableStateFlow(prefs.getBoolean("bubble_enabled", true))
    val bubbleEnabledFlow: StateFlow<Boolean> = _bubbleEnabled.asStateFlow()

    /** 悬浮窗是否开启（持久化偏好，供通知控制面板与服务使用）。 */
    var bubbleEnabled: Boolean
        get() = _bubbleEnabled.value
        set(value) {
            prefs.edit().putBoolean("bubble_enabled", value).apply()
            _bubbleEnabled.value = value
        }

    // OSD 悬浮窗：开关 + 实时数据（不落库）
    private val _osdEnabled = MutableStateFlow(prefs.getBoolean("osd_enabled", false))
    val osdEnabledFlow: StateFlow<Boolean> = _osdEnabled.asStateFlow()

    private val _osdMetrics = MutableStateFlow(loadOsdMetrics())
    val osdMetricsFlow: StateFlow<List<OsdMetric>> = _osdMetrics.asStateFlow()

    private val _osdSample = MutableStateFlow<OsdSample?>(null)
    val osdSample: StateFlow<OsdSample?> = _osdSample.asStateFlow()

    // OSD 上次成功采到的数据：某监控项刷新缓慢/瞬时读取失败时保持上次值继续显示，而不是该项缺失
    private var lastOsdSample: OsdSample? = null

    var osdEnabled: Boolean
        get() = _osdEnabled.value
        set(value) {
            prefs.edit().putBoolean("osd_enabled", value).apply()
            _osdEnabled.value = value
            if (value) startOsd() else stopOsd()
        }

    /** 设置 OSD 显示项及顺序（持久化）。 */
    fun setOsdMetrics(metrics: List<OsdMetric>) {
        prefs.edit().putString("osd_metrics", metrics.joinToString(",") { it.key }).apply()
        _osdMetrics.value = metrics
    }

    private fun loadOsdMetrics(): List<OsdMetric> {
        val saved = prefs.getString("osd_metrics", null) ?: return OsdMetric.entries
        return saved.split(',').mapNotNull { key -> OsdMetric.entries.firstOrNull { it.key == key } }
    }

    init {
        // 进程重启（崩溃/被杀）后终结上次未关闭的记录，避免悬空记录
        scope.launch { closeDanglingRecords() }
    }

    private suspend fun closeDanglingRecords() {
        val now = System.currentTimeMillis()
        recordDao.getOpenRecords().forEach { record ->
            val samples = sampleDao.getSamples(record.id)
            val stats = samples.toStats()
            recordDao.update(
                record.copy(
                    endedAtMs = now,
                    avgSocPowerUw = stats.avgFor("SOC"),
                    avgGpuPowerUw = stats.avgFor("GPU"),
                    avgCpuMPowerUw = stats.avgFor("CPU-M"),
                    avgCpuLPowerUw = stats.avgFor("CPU-L"),
                    avgNspPowerUw = stats.avgFor("NSP"),
                ),
            )
        }
    }

    suspend fun refreshDeviceState() {
        val rootOk = qptReader.hasRoot()
        if (!rootOk) {
            _state.update { it.copy(qptAvailable = false, qptEnabled = false, status = "Root 失败，请检查 KernelSU 授权") }
            return
        }
        val available = qptReader.isAvailable()
        if (!available) {
            _state.update { it.copy(qptAvailable = false, qptEnabled = false, status = "当前设备不支持 Qualcomm Power Telemetry") }
        }
        val enabled = if (available) qptReader.isEnabled() else false
        val zones = if (available) qptReader.scanZones() else emptyList()
        qptReader.cacheZones(zones)
        val clusters = auxReader.scanClusters()
        val tempSensors = auxReader.scanTempZones()
        val devicePowerAvailable = auxReader.isBatteryPowerAvailable()
        val gpuSensor = auxReader.scanGpuSensor()
        val ddrFreqPath = auxReader.scanDdrFreqPath()
        _state.update {
            it.copy(
                qptAvailable = available,
                qptEnabled = enabled,
                zones = zones,
                clusters = clusters,
                tempSensors = tempSensors,
                devicePowerAvailable = devicePowerAvailable,
                gpuSensor = gpuSensor,
                ddrFreqPath = ddrFreqPath,
                status = if (available) {
                    if (enabled) "QPT 已开启，发现 ${zones.size} 个 Zone" else "QPT 已关闭"
                } else {
                    "无 QPT 支持，仅记录频率/温度等数据"
                },
            )
        }
        // 应用启动/刷新后，若 QPT 已开启且未在记录，自动开始轻量预览
        if (enabled && samplerJob?.isActive != true) {
            startPreview()
        }
    }

    suspend fun setQptEnabled(enabled: Boolean) {
        if (!qptReader.setEnabled(enabled)) {
            _state.update { it.copy(status = "写入 enabled 失败，请检查 KernelSU 授权") }
            return
        }
        if (enabled) {
            refreshDeviceState()
            startPreview()
        } else {
            stopPreview()
            stopRecording()
            refreshDeviceState()
        }
    }

    fun setInterval(interval: SamplingInterval) {
        // 采样间隔持久化：重启后恢复上次选择
        prefs.edit().putString("sampling_interval", interval.name).apply()
        _state.update { it.copy(interval = interval) }
    }

    /**
     * 实时预览：QPT 开启时自动运行，只读 QPT 功耗（不落库、不采 FPS/aux），
     * 以极低开销刷新首页 latestSamples。记录期间由 record 循环接管，预览暂停。
     */
    private fun startPreview() {
        if (previewJob?.isActive == true) return
        val current = _state.value
        // OSD 开启时由其循环统一接管（同时刷新首页），避免两个循环各做一次 su 往返重复采样
        if (_osdEnabled.value && current.canRecord) return
        if (!current.qptEnabled || current.zones.isEmpty()) return
        val cachedCommand = qptReader.buildSampleCommand(current.zones)
        if (cachedCommand.isBlank()) return
        previewJob = scope.launch {
            var lastUi = 0L
            while (isActive && _state.value.qptEnabled) {
                val ts = System.currentTimeMillis()
                val samples = qptReader.parseSampleLines(rootManager.execute(cachedCommand), ts)
                if (samples.isNotEmpty() && ts - lastUi >= 500) {
                    lastUi = ts
                    _state.update { it.copy(latestSamples = samples) }
                }
                delay(_state.value.interval.millis)
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
    }

    /**
     * OSD 实时采集：读取全部监控项（QPT + aux + FPS）但不落库，同时刷新首页与 OSD 悬浮窗。
     * 记录期间由 record 循环接管（零额外读取）；非记录期间本循环独立运行并暂停 preview 避免重复采样。
     */
    private fun startOsd() {
        if (osdJob?.isActive == true) return
        val current = _state.value
        if (!current.canRecord) return
        // OSD 接管实时采样，暂停轻量 preview，避免双重 su 往返
        stopPreview()
        val cachedCommand = buildString {
            append(qptReader.buildSampleCommand(current.zones))
            val aux = auxReader.buildAuxCommand(current.clusters, current.tempSensors, current.gpuSensor, current.ddrFreqPath)
            if (isNotEmpty() && aux.isNotEmpty()) append("; ")
            append(aux)
        }
        if (cachedCommand.isBlank()) return
        osdJob = scope.launch {
            var lastFpsMs = 0L
            var lastUi = 0L
            var fps: Float? = null
            while (isActive) {
                val ts = System.currentTimeMillis()
                val lines = rootManager.execute(cachedCommand)
                val zones = qptReader.parseSampleLines(lines, ts)
                val aux = auxReader.parseAuxLines(lines, ts)
                // FPS 每 ~1s 采样一次（dumpsys 较重，sampleFps 内部单次往返解析前台包名 + 帧数）
                if (ts - lastFpsMs >= 1_000) {
                    lastFpsMs = ts
                    fps = auxReader.sampleFps()
                }
                // 节流：500ms 同时更新 OSD 与首页（一次读取，双用途）
                if (ts - lastUi >= 500) {
                    lastUi = ts
                    _osdSample.value = buildOsdSample(zones, aux, fps)
                    if (zones.isNotEmpty()) _state.update { it.copy(latestSamples = zones) }
                }
                delay(_state.value.interval.millis)
            }
        }
    }

    private fun stopOsd() {
        osdJob?.cancel()
        osdJob = null
        _osdSample.value = null
        lastOsdSample = null
        // OSD 关闭后，若 QPT 仍开启，恢复轻量预览刷新首页
        if (_state.value.qptEnabled) startPreview()
    }

    private fun buildOsdSample(zones: List<ZoneSample>, aux: AuxReader.AuxBatch, fps: Float?): OsdSample {
        // 一次遍历建立 zone 功耗与温度查找表，避免多次 firstOrNull 重复扫描
        val zonePower = zones.associate { it.zoneName.lowercase() to it.effectivePowerUw }
        val temps = aux.temps.associate { it.sensor to it.tempC }
        val prev = lastOsdSample
        return OsdSample(
            // 本轮读到新值用新值；未读到（采样周期慢/瞬时失败）保留上次值，避免该行消失
            socPowerUw = zonePower["soc"] ?: prev?.socPowerUw,
            cpuLPowerUw = zonePower["cpu-l"] ?: prev?.cpuLPowerUw,
            cpuMPowerUw = zonePower["cpu-m"] ?: prev?.cpuMPowerUw,
            gpuPowerUw = zonePower["gpu"] ?: prev?.gpuPowerUw,
            gpuFreqHz = aux.gpuFreqHz ?: prev?.gpuFreqHz,
            gpuUsagePercent = aux.gpuUsagePercent ?: prev?.gpuUsagePercent,
            fps = fps ?: prev?.fps,
            cpuTempC = temps["cpu"] ?: prev?.cpuTempC,
            gpuTempC = temps["gpu"] ?: prev?.gpuTempC,
            ddrTempC = temps["ddr"] ?: prev?.ddrTempC,
            devicePowerUw = aux.devicePowerUw ?: prev?.devicePowerUw,
        ).also { lastOsdSample = it }
    }

    /**
     * 开始记录。记录开始时读取当前前台应用并写入 record.appName。
     * 无需 QPT 支持：只要存在任意附加监控源（簇频率/温度/整机功耗/GPU/DDR）即可记录。
     * @param autoStopOnAppExit 悬浮球启动时传 true：前台应用离开被记录应用后自动停止记录。
     * @return 是否成功开始记录（无数据源/已在录制返回 false）
     */
    suspend fun startRecording(autoStopOnAppExit: Boolean = false): Boolean {
        refreshDeviceState()
        val current = _state.value
        if (!current.canRecord) {
            _state.update {
                it.copy(status = if (current.qptAvailable) "请开启 QPT 或检查监控源" else "无可用监控数据源")
            }
            return false
        }
        if (samplerJob?.isActive == true) return false

        val app = appInspector.currentForegroundApp()
        val startedAt = System.currentTimeMillis()
        val recordId = recordDao.insert(
            RecordEntity(
                appName = app.appName,
                packageName = app.packageName,
                iconPackage = app.iconPackage,
                startedAtMs = startedAt,
                endedAtMs = null,
            ),
        )
        activeRecord = RecordEntity(
            id = recordId,
            appName = app.appName,
            packageName = app.packageName,
            iconPackage = app.iconPackage,
            startedAtMs = startedAt,
            endedAtMs = null,
        )
        qptReader.resetDeltas()
        auxReader.resetDeltas()
        val qptNote = if (current.qptEnabled) "" else "（无 QPT）"
        _state.update { it.copy(isRecording = true, status = "记录中$qptNote：${app.appName}") }
        // 记录期间由 record 循环接管首页刷新，暂停预览
        stopPreview()

        // 采样循环内不变的量缓存到局部变量，避免每 tick 读取 StateFlow / 重建命令字符串
        val intervalMs = current.interval.millis
        val qptEnabled = current.qptEnabled
        val cachedCommand = buildString {
            append(qptReader.buildSampleCommand(current.zones))
            val aux = auxReader.buildAuxCommand(current.clusters, current.tempSensors, current.gpuSensor, current.ddrFreqPath)
            if (isNotEmpty() && aux.isNotEmpty()) append("; ")
            append(aux)
        }

        samplerJob = scope.launch {
            val recordPackage = app.packageName
            var lastAppCheckMs = 0L
            var lastFpsSampleMs = 0L
            var lastUiUpdateMs = 0L
            var lastFps: Float? = null
            try {
                while (isActive) {
                    val timestamp = System.currentTimeMillis()
                    if (cachedCommand.isNotBlank()) {
                        val lines = rootManager.execute(cachedCommand)
                        val qptSamples = if (qptEnabled) qptReader.parseSampleLines(lines, timestamp) else emptyList()
                        val auxBatch = auxReader.parseAuxLines(lines, timestamp)
                        // 单事务持久化：8 张表一次 WAL 提交，避免每 tick 多次事务开销
                        database.withTransaction {
                            if (qptSamples.isNotEmpty()) sampleDao.insertAll(qptSamples.map { it.toEntity(recordId) })
                            insertAuxBatchInTransaction(recordId, timestamp, auxBatch)
                        }
                        // OSD 悬浮窗：仅开启时才复用本轮数据构建（关闭时零开销）
                        if (_osdEnabled.value) {
                            _osdSample.value = buildOsdSample(qptSamples, auxBatch, lastFps)
                        }
                        // UI 节流：合并为一次 StateFlow 更新，减少 CAS + 对象分配 + Compose 重组
                        if (timestamp - lastUiUpdateMs >= 500) {
                            lastUiUpdateMs = timestamp
                            val devicePower = auxBatch.devicePowerUw
                            _state.update { s ->
                                s.copy(
                                    latestSamples = if (qptSamples.isNotEmpty()) qptSamples else s.latestSamples,
                                    latestDevicePowerUw = devicePower ?: s.latestDevicePowerUw,
                                )
                            }
                        }
                    }

                    // FPS：dumpsys gfxinfo 较重，每 ~1s 采样一次（记录期间）。
                    // sampleFps 内部单次 su 往返解析前台包名 + dump 帧数，跟随实际前台应用：
                    // 应用内按钮启动记录时前台是 WattScope 自身，切到目标应用后仍能统计其帧率。
                    if (timestamp - lastFpsSampleMs >= 1_000) {
                        lastFpsSampleMs = timestamp
                        val fps = auxReader.sampleFps()
                        if (fps != null) {
                            lastFps = fps
                            auxSampleDao.insertFps(listOf(FpsEntity(recordId = recordId, timestampMs = timestamp, fps = fps)))
                            if (timestamp - lastUiUpdateMs >= 500) {
                                lastUiUpdateMs = timestamp
                                _state.update { it.copy(latestFps = fps) }
                            }
                        }
                    }

                    // 悬浮窗自动停止：离开被记录应用即停（每 2s 检查一次，降低开销）
                    if (autoStopOnAppExit && timestamp - lastAppCheckMs >= 2_000) {
                        lastAppCheckMs = timestamp
                        if (!appInspector.isForeground(recordPackage)) break
                    }
                    delay(intervalMs)
                }
            } finally {
                // break 退出（应用离开）时自动终结记录。
                // 注意不能调用 stopRecording()：其内部 samplerJob?.cancel() 会取消当前协程自身，
                // 导致后续 DB 操作抛 CancellationException，记录无法闭合。
                if (isActive) {
                    samplerJob = null
                    finalizeActiveRecord()
                    if (_state.value.qptEnabled) startPreview()
                    if (_osdEnabled.value) startOsd()
                }
            }
        }
        return true
    }

    /** 事务内执行的附加监控项批量写入（不更新 UI 状态）。 */
    private suspend fun insertAuxBatchInTransaction(recordId: Long, timestampMs: Long, batch: AuxReader.AuxBatch) {
        if (batch.clusterFreqs.isNotEmpty()) {
            auxSampleDao.insertClusterFreqs(batch.clusterFreqs.map { it.toEntity(recordId) })
        }
        if (batch.temps.isNotEmpty()) {
            auxSampleDao.insertTemps(batch.temps.map { it.toEntity(recordId) })
        }
        batch.devicePowerUw?.let { power ->
            auxSampleDao.insertDevicePowers(listOf(DevicePowerEntity(recordId = recordId, timestampMs = timestampMs, powerUw = power)))
        }
        if (batch.coreUsages.isNotEmpty()) {
            auxSampleDao.insertCoreUsages(batch.coreUsages.map { it.toEntity(recordId) })
        }
        if (batch.gpuFreqHz != null) {
            auxSampleDao.insertGpus(
                listOf(GpuEntity(recordId = recordId, timestampMs = timestampMs, freqHz = batch.gpuFreqHz, usagePercent = batch.gpuUsagePercent)),
            )
        }
        if (batch.ddrFreqHz != null) {
            auxSampleDao.insertDdrFreqs(
                listOf(DdrFreqEntity(recordId = recordId, timestampMs = timestampMs, freqHz = batch.ddrFreqHz)),
            )
        }
    }

    suspend fun stopRecording() {
        samplerJob?.cancel()
        samplerJob = null
        finalizeActiveRecord()
        // 停止记录后：若 QPT 仍开启，恢复轻量实时预览（继续刷新首页）；OSD 恢复独立采集
        if (_state.value.qptEnabled) startPreview()
        if (_osdEnabled.value) startOsd()
    }

    private suspend fun finalizeActiveRecord() {
        val record = activeRecord ?: run {
            _state.update { it.copy(isRecording = false) }
            return
        }
        val samples = sampleDao.getSamples(record.id)
        val stats = samples.toStats()
        val avgFps = auxSampleDao.getFps(record.id).map { it.fps }.average().toFloat().takeIf { it > 0f }
        recordDao.update(
            record.copy(
                endedAtMs = System.currentTimeMillis(),
                avgSocPowerUw = stats.avgFor("SOC"),
                avgGpuPowerUw = stats.avgFor("GPU"),
                avgCpuMPowerUw = stats.avgFor("CPU-M"),
                avgCpuLPowerUw = stats.avgFor("CPU-L"),
                avgNspPowerUw = stats.avgFor("NSP"),
                avgFps = avgFps,
            ),
        )
        activeRecord = null
        _state.update { it.copy(isRecording = false, status = "记录已保存") }
    }

    fun observeRecord(id: Long) = recordDao.observeRecord(id)

    fun observeSamples(recordId: Long) = sampleDao.observeSamples(recordId)

    fun observeClusterFreqs(recordId: Long) = auxSampleDao.observeClusterFreqs(recordId)

    fun observeCoreUsages(recordId: Long) = auxSampleDao.observeCoreUsages(recordId)

    fun observeTemps(recordId: Long) = auxSampleDao.observeTemps(recordId)

    fun observeDevicePowers(recordId: Long) = auxSampleDao.observeDevicePowers(recordId)

    fun observeGpus(recordId: Long) = auxSampleDao.observeGpus(recordId)

    fun observeDdrFreqs(recordId: Long) = auxSampleDao.observeDdrFreqs(recordId)

    fun observeFps(recordId: Long) = auxSampleDao.observeFps(recordId)

    suspend fun deleteRecord(id: Long) = recordDao.delete(id)

    suspend fun deleteAll() = recordDao.deleteAll()

    /** 完整 JSON 导出：记录元数据 + 全部 8 类监控采样数据。 */
    suspend fun exportRecordJson(recordId: Long): String = withContext(Dispatchers.IO) {
        val record = recordDao.getRecord(recordId)
        val root = JSONObject()
        root.put("recordId", recordId)
        record?.let {
            root.put("appName", it.appName)
            root.put("packageName", it.packageName)
            root.put("startedAtMs", it.startedAtMs)
            root.put("endedAtMs", it.endedAtMs ?: JSONObject.NULL)
            root.put("avgSocPowerUw", it.avgSocPowerUw)
            root.put("avgGpuPowerUw", it.avgGpuPowerUw)
            root.put("avgCpuMPowerUw", it.avgCpuMPowerUw)
            root.put("avgCpuLPowerUw", it.avgCpuLPowerUw)
            root.put("avgNspPowerUw", it.avgNspPowerUw)
            root.put("avgFps", it.avgFps?.toDouble() ?: JSONObject.NULL)
        }
        root.put("samples", JSONArray(sampleDao.getSamples(recordId).map { it.toJson() }))
        root.put("clusterFreqs", JSONArray(auxSampleDao.getClusterFreqs(recordId).map {
            JSONObject().put("timestampMs", it.timestampMs).put("clusterName", it.clusterName).put("freqKhz", it.freqKhz)
        }))
        root.put("coreUsages", JSONArray(auxSampleDao.getCoreUsages(recordId).map {
            JSONObject().put("timestampMs", it.timestampMs).put("coreIndex", it.coreIndex).put("usagePercent", it.usagePercent.toDouble())
        }))
        root.put("temps", JSONArray(auxSampleDao.getTemps(recordId).map {
            JSONObject().put("timestampMs", it.timestampMs).put("sensor", it.sensor).put("tempC", it.tempC.toDouble())
        }))
        root.put("devicePowers", JSONArray(auxSampleDao.getDevicePowers(recordId).map {
            JSONObject().put("timestampMs", it.timestampMs).put("powerUw", it.powerUw)
        }))
        root.put("gpus", JSONArray(auxSampleDao.getGpus(recordId).map {
            JSONObject().put("timestampMs", it.timestampMs).put("freqHz", it.freqHz)
                .put("usagePercent", it.usagePercent?.toDouble() ?: JSONObject.NULL)
        }))
        root.put("ddrFreqs", JSONArray(auxSampleDao.getDdrFreqs(recordId).map {
            JSONObject().put("timestampMs", it.timestampMs).put("freqHz", it.freqHz)
        }))
        root.put("fps", JSONArray(auxSampleDao.getFps(recordId).map {
            JSONObject().put("timestampMs", it.timestampMs).put("fps", it.fps.toDouble())
        }))
        root.toString(2)
    }

    /** 完整 CSV 导出：长格式，type 列区分监控项。 */
    suspend fun exportRecordCsv(recordId: Long): String = withContext(Dispatchers.IO) {
        val record = recordDao.getRecord(recordId)
        buildString {
            // 元数据头部注释
            record?.let {
                appendLine("# recordId,$recordId")
                appendLine("# appName,${it.appName.replace(',', ';')}")
                appendLine("# packageName,${it.packageName}")
                appendLine("# startedAtMs,${it.startedAtMs}")
                appendLine("# endedAtMs,${it.endedAtMs ?: ""}")
            }
            appendLine("type,timestampMs,field1,field2,field3,field4")
            sampleDao.getSamples(recordId).forEach {
                appendLine("qpt,${it.timestampMs},${it.zoneName},${it.energyUj},${it.powerUw ?: ""},${it.computedPowerUw ?: ""}")
            }
            auxSampleDao.getClusterFreqs(recordId).forEach {
                appendLine("cluster_freq,${it.timestampMs},${it.clusterName},${it.freqKhz}")
            }
            auxSampleDao.getCoreUsages(recordId).forEach {
                appendLine("core_usage,${it.timestampMs},${it.coreIndex},${it.usagePercent}")
            }
            auxSampleDao.getTemps(recordId).forEach {
                appendLine("temp,${it.timestampMs},${it.sensor},${it.tempC}")
            }
            auxSampleDao.getDevicePowers(recordId).forEach {
                appendLine("device_power,${it.timestampMs},${it.powerUw}")
            }
            auxSampleDao.getGpus(recordId).forEach {
                appendLine("gpu,${it.timestampMs},${it.freqHz},${it.usagePercent ?: ""}")
            }
            auxSampleDao.getDdrFreqs(recordId).forEach {
                appendLine("ddr_freq,${it.timestampMs},${it.freqHz}")
            }
            auxSampleDao.getFps(recordId).forEach {
                appendLine("fps,${it.timestampMs},${it.fps}")
            }
        }
    }

    fun stats(samples: List<SampleEntity>): List<ZoneStats> = samples.toStats()

    private fun ZoneSample.toEntity(recordId: Long) = SampleEntity(
        recordId = recordId,
        zonePath = zonePath,
        zoneName = zoneName,
        timestampMs = timestampMs,
        energyUj = energyUj,
        powerUw = powerUw,
        computedPowerUw = computedPowerUw,
    )

    private fun ClusterFreqSample.toEntity(recordId: Long) = ClusterFreqEntity(
        recordId = recordId,
        timestampMs = timestampMs,
        clusterName = clusterName,
        freqKhz = freqKhz,
    )

    private fun CoreUsageSample.toEntity(recordId: Long) = CoreUsageEntity(
        recordId = recordId,
        timestampMs = timestampMs,
        coreIndex = coreIndex,
        usagePercent = usagePercent,
    )

    private fun TempSample.toEntity(recordId: Long) = TempEntity(
        recordId = recordId,
        timestampMs = timestampMs,
        sensor = sensor,
        tempC = tempC,
    )

    private fun List<SampleEntity>.toStats(): List<ZoneStats> = groupBy { it.zoneName }.mapNotNull { (zone, rows) ->
        val powers = rows.map { it.effectivePowerUw() }
        val peak = rows.maxByOrNull { it.effectivePowerUw() } ?: return@mapNotNull null
        val firstEnergy = rows.minByOrNull { it.timestampMs }?.energyUj ?: 0L
        val lastEnergy = rows.maxByOrNull { it.timestampMs }?.energyUj ?: firstEnergy
        ZoneStats(
            zoneName = zone,
            maxPowerUw = powers.maxOrNull() ?: 0L,
            minPowerUw = powers.minOrNull() ?: 0L,
            avgPowerUw = powers.average().toLong(),
            totalEnergyUj = (lastEnergy - firstEnergy).coerceAtLeast(0L),
            peakAtMs = peak.timestampMs,
        )
    }

    private fun List<ZoneStats>.avgFor(name: String): Long =
        firstOrNull { it.zoneName.equals(name, ignoreCase = true) }?.avgPowerUw ?: 0L

    private fun SampleEntity.effectivePowerUw(): Long = powerUw ?: computedPowerUw ?: 0L

    private fun SampleEntity.toJson(): JSONObject = JSONObject()
        .put("timestampMs", timestampMs)
        .put("zoneName", zoneName)
        .put("zonePath", zonePath)
        .put("energyUj", energyUj)
        .put("powerUw", powerUw)
        .put("computedPowerUw", computedPowerUw)
        .put("effectivePowerUw", effectivePowerUw())
}

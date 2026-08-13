package com.qpt.powermonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.qpt.powermonitor.data.local.ClusterFreqEntity
import com.qpt.powermonitor.data.local.CoreUsageEntity
import com.qpt.powermonitor.data.local.DdrFreqEntity
import com.qpt.powermonitor.data.local.DevicePowerEntity
import com.qpt.powermonitor.data.local.GpuEntity
import com.qpt.powermonitor.data.local.RecordEntity
import com.qpt.powermonitor.data.local.SampleEntity
import com.qpt.powermonitor.data.local.TempEntity
import com.qpt.powermonitor.domain.OsdMetric
import com.qpt.powermonitor.domain.SamplingInterval
import com.qpt.powermonitor.domain.ZoneSample
import com.qpt.powermonitor.domain.ZoneStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val ChartLeftPadding = 90f
private const val ChartRightPadding = 18f
private const val ChartBottomPadding = 36f

@Composable
fun QptApp(
    viewModel: QptViewModel,
    onRequestOverlay: () -> Unit,
    onStartService: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onOpenMiuiAutostart: () -> Unit,
    onToggleBubble: (Boolean) -> Unit,
    onExportRecord: (Long, Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val records by viewModel.records.collectAsState()
    val bubbleEnabled by viewModel.bubbleEnabled.collectAsState()
    val osdEnabled by viewModel.osdEnabled.collectAsState()
    val osdMetrics by viewModel.osdMetrics.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var detailId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.PlayArrow, null) }, label = { Text("Dashboard") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.Refresh, null) }, label = { Text("History") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> Dashboard(
                    status = state.status,
                    enabled = state.qptEnabled,
                    isRecording = state.isRecording,
                    canRecord = state.canRecord,
                    samples = state.latestSamples,
                    zoneNames = state.zones.map { it.name },
                    devicePowerAvailable = state.devicePowerAvailable,
                    devicePowerUw = state.latestDevicePowerUw,
                    onRefresh = viewModel::refresh,
                    onToggleEnabled = viewModel::setEnabled,
                    onRecordClick = { if (state.isRecording) viewModel.stopRecording() else viewModel.startRecording() },
                )
                1 -> History(records, onOpen = { detailId = it }, onDelete = viewModel::deleteRecord, onDeleteAll = viewModel::deleteAll)
                2 -> SettingsScreen(
                    enabled = state.qptEnabled,
                    interval = state.interval,
                    zones = state.zones.map { it.name },
                    bubbleEnabled = bubbleEnabled,
                    osdEnabled = osdEnabled,
                    osdMetrics = osdMetrics,
                    onToggleEnabled = viewModel::setEnabled,
                    onInterval = viewModel::setInterval,
                    onRequestOverlay = onRequestOverlay,
                    onStartService = onStartService,
                    onRequestBatteryWhitelist = onRequestBatteryWhitelist,
                    onOpenMiuiAutostart = onOpenMiuiAutostart,
                    onToggleBubble = onToggleBubble,
                    onToggleOsd = viewModel::setOsdEnabled,
                    onOsdMetricsChange = viewModel::setOsdMetrics,
                )
            }
        }
    }

    detailId?.let { id ->
        val detail by remember(id) { viewModel.stats(id) }.collectAsState()
        DetailSheet(
            detail = detail,
            onDismiss = { detailId = null },
            onExport = { asJson -> onExportRecord(id, asJson) },
        )
    }
}

@Composable
private fun Dashboard(
    status: String,
    enabled: Boolean,
    isRecording: Boolean,
    canRecord: Boolean,
    samples: List<ZoneSample>,
    zoneNames: List<String>,
    devicePowerAvailable: Boolean,
    devicePowerUw: Long?,
    onRefresh: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRecordClick: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            HeaderCard(status, enabled, isRecording, canRecord, samples, devicePowerAvailable, devicePowerUw, onRefresh, onToggleEnabled, onRecordClick)
        }
        item {
            Text("Live Power", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LiveChart(samples)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                zoneNames.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
            }
        }
        items(samples) { sample ->
            MetricRow(sample.zoneName, uwToW(sample.effectivePowerUw), ujToJ(sample.energyUj))
        }
    }
}

@Composable
private fun HeaderCard(
    status: String,
    enabled: Boolean,
    isRecording: Boolean,
    canRecord: Boolean,
    samples: List<ZoneSample>,
    devicePowerAvailable: Boolean,
    devicePowerUw: Long?,
    onRefresh: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRecordClick: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("WattScope", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(status, color = MaterialTheme.colorScheme.secondary)
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = onToggleEnabled)
                Text(if (enabled) "QPT Enable ON" else "QPT 已关闭")
                Spacer(Modifier.weight(1f))
                Button(onClick = onRecordClick, enabled = canRecord) {
                    Icon(if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isRecording) "Stop" else "Record")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryTile("SOC", uwToW(samples.firstOrNull { it.zoneName.equals("SOC", true) }?.effectivePowerUw ?: 0))
                SummaryTile("Zones", samples.size.toString())
                SummaryTile("State", if (isRecording) "REC" else "Idle")
            }
            if (devicePowerAvailable) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryTile("Device", devicePowerUw?.let { uwToW(it) } ?: "--")
                }
            }
        }
    }
}

@Composable
private fun AppIcon(packageName: String?, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val name = packageName?.takeIf { it.isNotBlank() } ?: context.packageName
                context.packageManager.getApplicationIcon(name).toBitmap().asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary)) {
        bitmap?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * 应用名显示组件：若存储的 appName 是包名（旧版本包可见性受限时回退为包名，如 "tv.danmaku.bili"），
 * 显示时异步解析真实应用名（应用显示名）；已卸载应用无法解析时回退包名。
 */
@Composable
private fun AppNameText(
    appName: String,
    packageName: String,
    style: TextStyle,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val context = LocalContext.current
    var display by remember(appName, packageName) { mutableStateOf(appName) }
    LaunchedEffect(appName, packageName) {
        if (appName == packageName && appName.contains('.')) {
            display = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                }.getOrElse { appName }
            }
        }
    }
    Text(display, style = style, fontWeight = fontWeight, color = color, maxLines = maxLines)
}

/** 一条折线：name 用于图例/读数，points 为 (时间戳, 数值)，与同组其他线对齐（同一 tick 采样）。 */
private data class SeriesData(
    val name: String,
    val color: Color,
    val points: List<Pair<Long, Float>>,
)

private val ChartPalette = listOf(
    Color(0xFF33D6A6), Color(0xFF8FD7FF), Color(0xFFFFCF66), Color(0xFFFF7A7A),
    Color(0xFFC9A7FF), Color(0xFFB4F17F), Color(0xFFFF9DE2), Color(0xFF7ED9E8),
)

/** 全局时间光标状态：吸附到的采样点 + 十字线像素位置 + 触摸点。 */
private data class CrosshairState(
    val index: Int,
    val ts: Long,
    val xPx: Float,
    val touchY: Float,
)

/** 通用时间序列折线图：共享全局十字光标 + 浮动 Tooltip。
 *  @param globalTs 共享时间光标（所有图表同步）；触摸本图时通过 onTsChange 上报吸附到的采样时间戳，松手置 null。
 *  Tooltip 仅在被触摸的图表显示；十字光标在所有图表同步。
 */
@Composable
private fun TimeSeriesChart(
    title: String,
    series: List<SeriesData>,
    formatter: (Float) -> String,
    interactive: Boolean = true,
    globalTs: Long? = null,
    onTsChange: ((Long?) -> Unit)? = null,
    rightSeries: SeriesData? = null,
    rightFormatter: (Float) -> String = { v -> "%.0f%%".format(Locale.US, v) },
    fixedMax: Float? = null,
    rightFixedMax: Float? = null,
    yTickCount: Int = 8,
    rightTickCount: Int = 7,
    showAverages: Boolean = true,
) {
    // 注意：状态与手势检测器不能用 series 作为 remember/pointerInput 的 key——
    // Room 表级失效会导致录制期间详情流持续重发射、series 重建，key 变化会重置状态并中断手势。
    var touching by remember { mutableStateOf(false) }
    var touchY by remember { mutableStateOf(0f) }
    var widthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val chartHeightPx = with(density) { 160.dp.toPx() }

    val refSeries = series.maxByOrNull { it.points.size }
    val refSize = refSeries?.points?.size ?: 0
    val refTsList = remember(series) { refSeries?.points?.map { it.first } ?: emptyList() }
    val allPoints = remember(series, rightSeries) { series.flatMap { it.points } + (rightSeries?.points ?: emptyList()) }
    val minTs = allPoints.minOfOrNull { it.first } ?: 0L
    val maxTs = allPoints.maxOfOrNull { it.first } ?: minTs
    val durationMs = (maxTs - minTs).coerceAtLeast(1L)
    // 当前图表的十字线采样索引（按共享时间戳对齐；触摸本图时直接用触摸索引）
    val crosshairIndex = globalTs?.let { ts ->
        refTsList.indexOf(ts).takeIf { it >= 0 } ?: nearestByTs(ts, refTsList)
    }
    // 全局十字光标激活时，所有图表（含非触摸图表）都显示各自数据 Tooltip
    val showTooltip = interactive && globalTs != null && crosshairIndex != null

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("$refSize samples", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
            }
            Box(Modifier.fillMaxWidth()) {
                val canvasModifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .onSizeChanged { widthPx = it.width }
                    .then(
                        if (interactive && onTsChange != null) {
                            Modifier.pointerInput(Unit) {
                                // 按下/拖拽：吸附并上报采样时间戳；松手/取消：清空（十字光标全图同步消失）
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    var lastIdx = nearestTsIndex(down.position.x, size.width.toFloat(), refTsList)
                                    onTsChange(refTsList.getOrNull(lastIdx))
                                    touching = true
                                    touchY = down.position.y
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break // 松手/取消
                                        val idx = nearestTsIndex(change.position.x, size.width.toFloat(), refTsList)
                                        if (idx != lastIdx) {
                                            lastIdx = idx
                                            onTsChange(refTsList.getOrNull(idx))
                                        }
                                        touchY = change.position.y
                                    }
                                    touching = false
                                    onTsChange(null)
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
                Canvas(canvasModifier) {
                    drawSeriesChart(
                        series, formatter, crosshairIndex, minTs, durationMs, rightSeries, fixedMax,
                        rightFormatter, rightFixedMax, yTickCount, rightTickCount,
                    )
                }
                if (showTooltip) {
                    val ts = globalTs ?: return@Box
                    // 非触摸图表用默认纵向位置（避免 Tooltip 重叠），触摸图表跟随手指
                    val effTouchY = if (touching) touchY else chartHeightPx * 0.4f
                    val c = CrosshairState(index = crosshairIndex ?: 0, ts = ts, xPx = crosshairX(ts, minTs, durationMs, widthPx), touchY = effTouchY)
                    CrosshairTooltip(
                        series = series,
                        index = c.index,
                        relativeSeconds = (c.ts - minTs) / 1000f,
                        anchorX = c.xPx,
                        touchY = c.touchY,
                        chartWidth = widthPx.toFloat(),
                        chartHeight = chartHeightPx,
                        formatter = formatter,
                        rightSeries = rightSeries,
                        rightFormatter = rightFormatter,
                    )
                }
            }
            if (showAverages) {
                ChartAverages(series, formatter, rightSeries, rightFormatter)
            }
        }
    }
}

/** 图表下方数据平均值行：每个系列一条 "名称 AVG 值"（颜色小圆点对应图例）。 */
@Composable
private fun ChartAverages(
    series: List<SeriesData>,
    formatter: (Float) -> String,
    rightSeries: SeriesData? = null,
    rightFormatter: (Float) -> String = { v -> "%.0f%%".format(Locale.US, v) },
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        series.forEach { s ->
            if (s.points.isNotEmpty()) {
                AvgChip(s.name, formatter(s.points.map { it.second }.average().toFloat()), s.color)
            }
        }
        rightSeries?.let { s ->
            if (s.points.isNotEmpty()) {
                AvgChip(s.name, rightFormatter(s.points.map { it.second }.average().toFloat()), s.color)
            }
        }
    }
}

@Composable
private fun AvgChip(name: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text("$name AVG $value", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

private fun nearestTsIndex(touchX: Float, width: Float, tsList: List<Long>): Int {
    if (tsList.isEmpty()) return 0
    val chartWidth = (width - ChartLeftPadding - ChartRightPadding).coerceAtLeast(1f)
    val ratio = ((touchX - ChartLeftPadding) / chartWidth).coerceIn(0f, 1f)
    return min(tsList.lastIndex, max(0, (ratio * tsList.lastIndex).roundToInt()))
}

private fun nearestByTs(ts: Long, tsList: List<Long>): Int? {
    if (tsList.isEmpty()) return null
    var best = 0
    var bestDiff = Long.MAX_VALUE
    tsList.forEachIndexed { i, t ->
        val diff = kotlin.math.abs(t - ts)
        if (diff < bestDiff) { bestDiff = diff; best = i }
    }
    return best
}

private fun crosshairX(ts: Long, minTs: Long, durationMs: Long, widthPx: Int): Float {
    val chartWidth = (widthPx - ChartLeftPadding - ChartRightPadding).coerceAtLeast(1f)
    return ChartLeftPadding + ((ts - minTs).toFloat() / durationMs) * chartWidth
}

/** 十字光标数据卡片：深色半透明、圆角、阴影；显示该采样点所有曲线的值；自动避让图表边界。 */
@Composable
private fun CrosshairTooltip(
    series: List<SeriesData>,
    index: Int,
    relativeSeconds: Float,
    anchorX: Float,
    touchY: Float,
    chartWidth: Float,
    chartHeight: Float,
    formatter: (Float) -> String,
    rightSeries: SeriesData? = null,
    rightFormatter: (Float) -> String = { v -> "%.0f%%".format(Locale.US, v) },
) {
    var tooltipSize by remember { mutableStateOf(IntSize.Zero) }
    val offsetX = if (anchorX + tooltipSize.width + 14 <= chartWidth) {
        anchorX + 14f
    } else {
        (anchorX - tooltipSize.width - 14f).coerceAtLeast(4f)
    }
    val offsetY = if (touchY - tooltipSize.height - 12 >= 0f) {
        touchY - tooltipSize.height - 12f
    } else {
        (touchY + 12f).coerceAtMost(chartHeight - tooltipSize.height - 4f)
    }
    Box(
        Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .onSizeChanged { tooltipSize = it },
    ) {
        Column(
            Modifier
                .shadow(6.dp, RoundedCornerShape(8.dp))
                .background(Color(0xE6141A22), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "Time: %.2fs".format(Locale.US, relativeSeconds),
                color = Color(0xFF9FB2C4),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            series.forEach { s ->
                val value = s.points.getOrNull(index)?.second
                if (value != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(s.color))
                        Spacer(Modifier.width(6.dp))
                        Text(s.name, color = Color(0xFFC7D3DE), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(formatter(value), color = s.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            rightSeries?.let { s ->
                val value = s.points.getOrNull(index)?.second
                if (value != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(s.color))
                        Spacer(Modifier.width(6.dp))
                        Text(s.name, color = Color(0xFFC7D3DE), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(rightFormatter(value), color = s.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun nearestSeriesIndex(touchX: Float, width: Float, count: Int): Int? {
    if (count <= 0) return null
    val chartWidth = (width - ChartLeftPadding - ChartRightPadding).coerceAtLeast(1f)
    val ratio = ((touchX - ChartLeftPadding) / chartWidth).coerceIn(0f, 1f)
    return min(count - 1, max(0, (ratio * (count - 1)).roundToInt()))
}

private fun DrawScope.drawSeriesChart(
    series: List<SeriesData>,
    formatter: (Float) -> String,
    selectedIndex: Int?,
    minTs: Long,
    durationMs: Long,
    rightSeries: SeriesData? = null,
    fixedMax: Float? = null,
    rightFormatter: (Float) -> String = { v -> "%.0f%%".format(Locale.US, v) },
    rightFixedMax: Float? = null,
    yTickCount: Int = 8,
    rightTickCount: Int = 7,
) {
    val left = ChartLeftPadding
    val right = size.width - ChartRightPadding
    val top = 18f
    val bottom = size.height - ChartBottomPadding
    val chartWidth = (right - left).coerceAtLeast(1f)
    val chartHeight = (bottom - top).coerceAtLeast(1f)
    val axisColor = Color(0xFF465160)
    val gridColor = Color(0xFF2B3442)
    val labelColor = android.graphics.Color.rgb(190, 203, 214)
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = 22f
    }

    drawLine(axisColor, Offset(left, top), Offset(left, bottom), strokeWidth = 2f)
    drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 2f)

    val allPoints = series.flatMap { it.points } + (rightSeries?.points ?: emptyList())
    if (allPoints.isEmpty()) return

    val maxValue = max(1f, fixedMax ?: allPoints.maxOf { it.second })
    val rightMax = if (rightSeries != null) max(1f, rightFixedMax ?: (rightSeries.points.maxOfOrNull { it.second } ?: 1f)) else 0f

    // 纵坐标分度：按目标网格数取"整步长"刻度（CPU 占用等固定上限图可精确每 10%）
    niceTicks(maxValue, yTickCount).forEach { value ->
        val y = bottom - (value / maxValue) * chartHeight
        drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
        drawContext.canvas.nativeCanvas.drawText(formatter(value), 0f, y + 8f, textPaint)
    }
    repeat(5) { i ->
        val ratio = i / 4f
        val x = left + ratio * chartWidth
        drawLine(gridColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
        drawContext.canvas.nativeCanvas.drawText("${((durationMs * ratio) / 1000f).roundToInt()}s", x - 18f, size.height - 8f, textPaint)
    }
    if (rightSeries != null) {
        niceTicks(rightMax, rightTickCount).forEach { value ->
            val y = bottom - (value / rightMax) * chartHeight
            drawContext.canvas.nativeCanvas.drawText(rightFormatter(value), right + 6f, y + 8f, textPaint)
        }
    }

    series.forEach { s ->
        if (s.points.size < 2) return@forEach
        val path = Path()
        s.points.forEachIndexed { index, (ts, value) ->
            val x = left + ((ts - minTs).toFloat() / durationMs) * chartWidth
            val y = bottom - (value / maxValue) * chartHeight
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, s.color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
    rightSeries?.let { rs ->
        if (rs.points.size >= 2) {
            val path = Path()
            rs.points.forEachIndexed { index, (ts, value) ->
                val x = left + ((ts - minTs).toFloat() / durationMs) * chartWidth
                val y = bottom - (value / rightMax) * chartHeight
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, rs.color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        }
    }

    // 全局十字光标：竖线 + 各曲线数据点
    selectedIndex?.let { idx ->
        val ref = series.maxByOrNull { it.points.size } ?: return@let
        val point = ref.points.getOrNull(idx) ?: return@let
        val x = left + ((point.first - minTs).toFloat() / durationMs) * chartWidth
        drawLine(Color(0xCCFFFFFF), Offset(x, top), Offset(x, bottom), strokeWidth = 1.5f)
        series.forEach { s ->
            s.points.getOrNull(idx)?.let { p ->
                val y = bottom - (p.second / maxValue) * chartHeight
                drawCircle(s.color, 6f, Offset(x, y))
                drawCircle(Color(0xAAFFFFFF), 6f, Offset(x, y), style = Stroke(width = 2f))
            }
        }
        rightSeries?.points?.getOrNull(idx)?.let { p ->
            val y = bottom - (p.second / rightMax) * chartHeight
            drawCircle(rightSeries.color, 6f, Offset(x, y))
            drawCircle(Color(0xAAFFFFFF), 6f, Offset(x, y), style = Stroke(width = 2f))
        }
    }
}

private fun formatFreqHz(f: Float): String =
    if (f >= 1_000_000_000f) "%.2f GHz".format(Locale.US, f / 1_000_000_000f)
    else if (f >= 1_000_000f) "%.0f MHz".format(Locale.US, f / 1_000_000f)
    else "%.0f kHz".format(Locale.US, f / 1_000f)

/** 生成从 0 到 max 的"整步长"纵坐标刻度：步长取 1/2/5×10ⁿ 的向上取整，避免碎刻度。 */
private fun niceTicks(max: Float, targetCount: Int): List<Float> {
    if (max <= 0f) return listOf(0f)
    val slots = (targetCount - 1).coerceAtLeast(1)
    val raw = max / slots
    val mag = 10f.pow(floor(log10(raw.toDouble())).toFloat())
    val step = (ceil(raw / mag) * mag).coerceAtLeast(1e-6f)
    val ticks = ArrayList<Float>(slots + 1)
    var v = 0f
    while (v <= max + step * 1e-3f) {
        ticks += v
        v += step
    }
    return ticks
}

/** 附加监控项折线图区：FPS / CPU 簇频率 / CPU 核心占用 / GPU(频率+负载双轴) / 整机功耗 / DDR。
 *  @param globalTs 共享十字光标时间戳；@param onTsChange 触摸上报回调（松手置 null）。
 */
@Composable
private fun AuxCharts(
    detail: DetailUiState,
    globalTs: Long?,
    onTsChange: (Long?) -> Unit,
) {
    // 从簇频率名（如 "CPU0-5"/"CPU6-7"）解析各簇核心范围，用于按簇着色
    val clusterRanges = remember(detail.clusterFreqSamples) {
        detail.clusterFreqSamples.map { it.clusterName }.distinct()
            .mapNotNull { name -> parseCpuRanges(name).takeIf { it.isNotEmpty() }?.let { name to it } }
            .sortedBy { (_, ranges) -> ranges.first().first }
    }
    val clusterColor = { index: Int ->
        when (index) {
            0 -> Color(0xFF33D6A6)   // 第一簇：绿
            1 -> Color(0xFFFF9800)   // 第二簇：橙
            else -> ChartPalette[index % ChartPalette.size]
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1. FPS
        detail.fpsSamples.takeIf { it.isNotEmpty() }?.let { fps ->
            TimeSeriesChart(
                "FPS",
                listOf(SeriesData("FPS", ChartPalette[0], fps.map { it.timestampMs to it.fps })),
                formatter = { v -> "%.1f".format(Locale.US, v) },
                globalTs = globalTs,
                onTsChange = onTsChange,
            )
        }
        // 2. CPU 簇频率
        detail.clusterFreqSamples.takeIf { it.isNotEmpty() }?.let { freqs ->
            val series = freqs.groupBy { it.clusterName }.entries
                .sortedBy { (name, _) -> clusterRanges.indexOfFirst { it.first == name }.takeIf { it >= 0 } ?: Int.MAX_VALUE }
                .mapIndexed { i, (name, rows) ->
                    SeriesData(name, clusterColor(i), rows.map { it.timestampMs to it.freqKhz * 1000f })
                }
            TimeSeriesChart("CPU 簇频率", series, ::formatFreqHz, globalTs = globalTs, onTsChange = onTsChange)
        }
        // 3. CPU 核心占用（纵轴固定最高 100%，不提供触摸）
        detail.coreUsageSamples.takeIf { it.isNotEmpty() }?.let { cores ->
            val series = cores.groupBy { it.coreIndex }.entries.map { (core, rows) ->
                // 按簇着色：属于第 i 簇的核心用该簇颜色（绿/橙），无簇信息时回退调色板
                val clusterIdx = clusterRanges.indexOfFirst { (_, ranges) -> ranges.any { core in it } }
                val color = if (clusterIdx >= 0) clusterColor(clusterIdx) else ChartPalette[core % ChartPalette.size]
                SeriesData("C$core", color, rows.map { it.timestampMs to it.usagePercent })
            }.sortedBy { it.name.removePrefix("C").toIntOrNull() ?: 0 }
            TimeSeriesChart(
                "CPU 核心占用",
                series,
                { v -> "%.0f%%".format(Locale.US, v) },
                interactive = false,
                fixedMax = 100f,
                yTickCount = 11, // 纵坐标每 10% 一个分度
            )
        }
        // 4. 整机功耗
        detail.devicePowerSamples.takeIf { it.isNotEmpty() }?.let { powers ->
            TimeSeriesChart(
                "整机功耗",
                listOf(SeriesData("整机功耗", ChartPalette[0], powers.map { it.timestampMs to it.powerUw / 1_000_000f })),
                formatter = { v -> "%.2f W".format(Locale.US, v) },
                globalTs = globalTs,
                onTsChange = onTsChange,
            )
        }
        // 5. DDR 频率
        detail.ddrFreqSamples.takeIf { it.isNotEmpty() }?.let { ddr ->
            TimeSeriesChart(
                "DDR 频率",
                listOf(SeriesData("DDR", ChartPalette[0], ddr.map { it.timestampMs to it.freqHz.toFloat() })),
                ::formatFreqHz,
                globalTs = globalTs,
                onTsChange = onTsChange,
            )
        }
    }
}

/** GPU 频率 + 占用 合并双轴图（独立展示，位于 cpu-m zone 图之后）。 */
@Composable
private fun GpuMergedChart(
    detail: DetailUiState,
    globalTs: Long?,
    onTsChange: ((Long?) -> Unit)?,
) {
    detail.gpuSamples.takeIf { it.isNotEmpty() }?.let { gpus ->
        val freq = SeriesData("频率", ChartPalette[0], gpus.map { it.timestampMs to it.freqHz.toFloat() })
        val usage = gpus.mapNotNull { it.usagePercent?.let { p -> it.timestampMs to p } }
        if (usage.isNotEmpty()) {
            TimeSeriesChart(
                "GPU 频率 & 占用",
                listOf(freq),
                ::formatFreqHz,
                globalTs = globalTs,
                onTsChange = onTsChange,
                rightSeries = SeriesData("占用", ChartPalette[1], usage),
                rightFormatter = { v -> "%.0f%%".format(Locale.US, v) },
                rightFixedMax = 100f,
                rightTickCount = 11, // 右侧占用纵坐标每 10% 一个分度
            )
        } else {
            TimeSeriesChart("GPU 频率", listOf(freq), ::formatFreqHz, globalTs = globalTs, onTsChange = onTsChange)
        }
    }
}

/** 温度折线图（详情页最后展示）。 */
@Composable
private fun TempChart(
    detail: DetailUiState,
    globalTs: Long?,
    onTsChange: (Long?) -> Unit,
) {
    detail.tempSamples.takeIf { it.isNotEmpty() }?.let { temps ->
        val series = temps.groupBy { it.sensor }.entries.mapIndexed { i, (sensor, rows) ->
            SeriesData(sensor.uppercase(), ChartPalette[i % ChartPalette.size], rows.map { it.timestampMs to it.tempC })
        }
        TimeSeriesChart("温度", series, formatter = { v -> "%.1f °C".format(Locale.US, v) }, globalTs = globalTs, onTsChange = onTsChange)
    }
}

/** 解析 "CPU0 1 2 3 4 5"/"CPU0-5"/"CPU0,2,4"/"CPU6 7" 形式的簇名为连续核心区间列表。 */
private fun parseCpuRanges(name: String): List<IntRange> {
    val body = name.removePrefix("CPU").trim()
    if (body.isEmpty()) return emptyList()
    val nums = mutableListOf<Int>()
    body.split(',', ' ', '\t').forEach { seg ->
        val s = seg.trim()
        if (s.isEmpty()) return@forEach
        if ('-' in s) {
            val parts = s.split('-')
            val a = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return@forEach
            val b = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return@forEach
            nums += (min(a, b)..max(a, b))
        } else {
            s.toIntOrNull()?.let { nums += it }
        }
    }
    if (nums.isEmpty()) return emptyList()
    val sorted = nums.sorted().distinct()
    val ranges = mutableListOf<IntRange>()
    var start = sorted[0]
    var prev = sorted[0]
    for (i in 1 until sorted.size) {
        if (sorted[i] == prev + 1) {
            prev = sorted[i]
        } else {
            ranges += start..prev
            start = sorted[i]
            prev = sorted[i]
        }
    }
    ranges += start..prev
    return ranges
}

@Composable
private fun SummaryTile(label: String, value: String) {
    Column(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .width(84.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LiveChart(samples: List<ZoneSample>) {
    val colors = listOf(Color(0xFF33D6A6), Color(0xFF8FD7FF), Color(0xFFFFCF66), Color(0xFFFF7A7A), Color(0xFFC9A7FF))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(220.dp).padding(12.dp)) {
            drawLine(Color(0xFF2B3442), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            val maxPower = max(1L, samples.maxOfOrNull { it.effectivePowerUw } ?: 1L).toFloat()
            samples.forEachIndexed { index, sample ->
                val x = if (samples.size == 1) size.width / 2 else size.width * index / (samples.size - 1)
                val y = size.height - (sample.effectivePowerUw / maxPower) * size.height
                drawCircle(colors[index % colors.size], 6f, Offset(x, y))
                drawLine(colors[index % colors.size], Offset(x, size.height), Offset(x, y), strokeWidth = 3f, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun MetricRow(name: String, power: String, energy: String) {
    ListItem(
        headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(energy) },
        trailingContent = { Text(power, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
    )
}

@Composable
private fun History(
    records: List<RecordEntity>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onDeleteAll: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onDeleteAll) { Text("Clear") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(records, key = { it.id }) { record ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(record.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(record.iconPackage ?: record.packageName, Modifier.size(42.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            AppNameText(record.appName, record.packageName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${formatDate(record.startedAtMs)} · ${duration(record)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            Text(
                                "AVG SOC ${uwToW(record.avgSocPowerUw)}  FPS ${record.avgFps?.let { "%.1f".format(Locale.US, it) } ?: "--"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        IconButton(onClick = { onDelete(record.id) }) { Icon(Icons.Default.Delete, null) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    enabled: Boolean,
    interval: SamplingInterval,
    zones: List<String>,
    bubbleEnabled: Boolean,
    osdEnabled: Boolean,
    osdMetrics: List<OsdMetric>,
    onToggleEnabled: (Boolean) -> Unit,
    onInterval: (SamplingInterval) -> Unit,
    onRequestOverlay: () -> Unit,
    onStartService: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onOpenMiuiAutostart: () -> Unit,
    onToggleBubble: (Boolean) -> Unit,
    onToggleOsd: (Boolean) -> Unit,
    onOsdMetricsChange: (List<OsdMetric>) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item {
            ListItem(
                headlineContent = { Text("QPT Enable") },
                supportingContent = { Text(if (enabled) "采样依赖 enabled=1" else "关闭时停止采样") },
                trailingContent = { Switch(checked = enabled, onCheckedChange = onToggleEnabled) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("悬浮窗") },
                supportingContent = { Text(if (bubbleEnabled) "悬浮球已开启" else "悬浮球已关闭") },
                trailingContent = { Switch(checked = bubbleEnabled, onCheckedChange = onToggleBubble) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("OSD 悬浮窗") },
                supportingContent = { Text(if (osdEnabled) "实时数据悬浮显示（不记录）" else "已关闭") },
                trailingContent = { Switch(checked = osdEnabled, onCheckedChange = onToggleOsd) },
            )
        }
        item {
            Text("OSD 显示项", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        }
        items(OsdMetric.entries) { metric ->
            val selected = osdMetrics.contains(metric)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { checked ->
                    onOsdMetricsChange(
                        if (checked) osdMetrics + metric else osdMetrics - metric,
                    )
                })
                Text(metric.label, Modifier.weight(1f))
            }
        }
        item {
            Text("Sampling interval", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SamplingInterval.entries.forEach {
                    AssistChip(onClick = { onInterval(it) }, label = { Text(if (it == interval) "${it.label} ✓" else it.label) })
                }
            }
        }
        item {
            Button(onClick = onRequestOverlay, modifier = Modifier.fillMaxWidth()) { Text("SYSTEM_ALERT_WINDOW") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onStartService, modifier = Modifier.fillMaxWidth()) { Text("Restart foreground service") }
        }
        item {
            Text("后台保活（MIUI 可能清理后台进程，建议加入白名单）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Button(onClick = onRequestBatteryWhitelist, modifier = Modifier.fillMaxWidth()) { Text("电池优化白名单") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenMiuiAutostart, modifier = Modifier.fillMaxWidth()) { Text("MIUI 自启动管理") }
        }
        item {
            Text("显示项目", fontWeight = FontWeight.Bold)
        }
        items(zones) { zone ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = true, onCheckedChange = {})
                Text(zone)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(detail: DetailUiState, onDismiss: () -> Unit, onExport: (Boolean) -> Unit) {
    val visibleZones = remember(detail.stats) { mutableStateMapOf<String, Boolean>().apply { detail.stats.forEach { put(it.zoneName, true) } } }
    // 全局共享十字光标：任一图表触摸时同步到所有图表，松手清空
    var crosshairTs by remember { mutableStateOf<Long?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Record Detail", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onExport(true) }) { Text("JSON") }
                        TextButton(onClick = { onExport(false) }) { Text("CSV") }
                    }
                    detail.record?.let { record ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppNameText(record.appName, record.packageName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                            Text(
                                " · ${formatDate(record.startedAtMs)} · ${duration(record)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
            item {
                AvgPowerSection(detail)
            }
            item {
                StatGrid(detail.stats, detail.samples.size, detail.avgFps)
            }
            if (detail.hasChartData) {
                item {
                    AuxCharts(detail, crosshairTs, { crosshairTs = it })
                }
            }
            item {
                ZoneCharts(detail, detail.samples.filter { visibleZones[it.zoneName] != false }, crosshairTs, { crosshairTs = it })
            }
            item {
                TempChart(detail, crosshairTs, { crosshairTs = it })
            }
            items(detail.stats) { stat ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = visibleZones[stat.zoneName] != false, onCheckedChange = { visibleZones[stat.zoneName] = it })
                    Text(stat.zoneName, Modifier.weight(1f))
                    Text("AVG ${uwToW(stat.avgPowerUw)}")
                }
            }
        }
    }
}

@Composable
private fun AvgPowerSection(detail: DetailUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("平均功耗", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            detail.stats.forEach { stat ->
                // 不显示 AVG SOC（其余 zone 与整机功耗照常）
                if (!stat.zoneName.equals("SOC", true)) {
                    SummaryTile(stat.zoneName.uppercase(), uwToW(stat.avgPowerUw))
                }
            }
            if (detail.avgDevicePowerUw > 0) {
                SummaryTile("DEVICE", uwToW(detail.avgDevicePowerUw))
            }
        }
    }
}

@Composable
private fun AuxSection(detail: DetailUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("其他监控", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (detail.avgDevicePowerUw > 0) {
                    Row {
                        Text("整机功耗", Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                        Text(uwToW(detail.avgDevicePowerUw), fontWeight = FontWeight.Bold)
                    }
                }
                detail.clusterAvgFreqs.forEach { (cluster, khz) ->
                    Row {
                        Text("$cluster 频率", Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                        Text(formatFreqHz(khz * 1000f), fontWeight = FontWeight.Bold)
                    }
                }
                if (detail.avgGpuFreqHz > 0) {
                    Row {
                        Text("GPU 频率", Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                        Text(formatFreqHz(detail.avgGpuFreqHz.toFloat()), fontWeight = FontWeight.Bold)
                    }
                }
                if (detail.avgGpuUsage > 0f) {
                    Row {
                        Text("GPU 占用", Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                        Text("%.1f%%".format(Locale.US, detail.avgGpuUsage), fontWeight = FontWeight.Bold)
                    }
                }
                if (detail.avgDdrFreqHz > 0) {
                    Row {
                        Text("DDR 频率", Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                        Text(formatFreqHz(detail.avgDdrFreqHz.toFloat()), fontWeight = FontWeight.Bold)
                    }
                }
                detail.sensorAvgTemps.forEach { (sensor, tempC) ->
                    Row {
                        Text("${sensor.uppercase()} 温度", Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                        Text("%.1f °C".format(Locale.US, tempC), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatGrid(stats: List<ZoneStats>, sampleCount: Int, avgFps: Float) {
    val soc = stats.firstOrNull { it.zoneName.equals("SOC", true) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTile("MAX SOC", uwToW(soc?.maxPowerUw ?: 0))
            SummaryTile("AVG SOC", uwToW(soc?.avgPowerUw ?: 0))
            SummaryTile("MIN SOC", uwToW(soc?.minPowerUw ?: 0))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTile("Samples", sampleCount.toString())
            SummaryTile("AVG FPS", if (avgFps > 0f) "%.1f".format(Locale.US, avgFps) else "--")
        }
    }
}

@Composable
private fun ZoneCharts(
    detail: DetailUiState,
    samples: List<SampleEntity>,
    globalTs: Long? = null,
    onTsChange: ((Long?) -> Unit)? = null,
) {
    // zone 图表自定义顺序：soc → cpu-m → GPU(合并) → cpu-l → gpu → nsp → debug-*
    val order = listOf("soc", "cpu-m", "cpu-l", "gpu", "nsp", "debug-0", "debug-2", "debug-4")
    val grouped = samples.groupBy { it.zoneName }
    val ordered = grouped.entries.sortedBy { (name, _) -> order.indexOf(name).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    val colors = listOf(Color(0xFF33D6A6), Color(0xFF8FD7FF), Color(0xFFFFCF66), Color(0xFFFF7A7A), Color(0xFFC9A7FF), Color(0xFFB4F17F))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ordered.forEachIndexed { index, entry ->
            ZonePowerChart(
                zoneName = entry.key,
                samples = entry.value,
                lineColor = colors[index % colors.size],
                globalTs = globalTs,
                onTsChange = onTsChange,
            )
            // GPU 频率&占用合并图紧跟 cpu-l 之后
            if (entry.key.equals("cpu-l", ignoreCase = true)) {
                GpuMergedChart(detail, globalTs, onTsChange)
            }
        }
    }
}

@Composable
private fun ZonePowerChart(
    zoneName: String,
    samples: List<SampleEntity>,
    lineColor: Color,
    globalTs: Long? = null,
    onTsChange: ((Long?) -> Unit)? = null,
) {
    var touching by remember { mutableStateOf(false) }
    var touchY by remember { mutableStateOf(0f) }
    val tsList = remember(samples) { samples.map { it.timestampMs } }
    val crosshairIndex = globalTs?.let { ts ->
        tsList.indexOf(ts).takeIf { it >= 0 } ?: nearestByTs(ts, tsList)
    }
    // 全局十字光标激活时，所有 zone 图表也显示各自数据 Tooltip
    val showTooltip = crosshairIndex != null && globalTs != null

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(zoneName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${samples.size} samples", color = MaterialTheme.colorScheme.secondary)
            }
            Box(Modifier.fillMaxWidth()) {
                val minTs = tsList.minOrNull() ?: 0L
                val maxTs = tsList.maxOrNull() ?: minTs
                val durationMs = (maxTs - minTs).coerceAtLeast(1L)
                var widthPx by remember { mutableStateOf(0) }
                val density = LocalDensity.current
                val chartHeightPx = with(density) { 220.dp.toPx() }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .onSizeChanged { widthPx = it.width }
                        .then(
                            if (onTsChange != null) {
                                Modifier.pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        var lastIdx = nearestTsIndex(down.position.x, size.width.toFloat(), tsList)
                                        onTsChange(tsList.getOrNull(lastIdx))
                                        touching = true
                                        touchY = down.position.y
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break // 松手/取消
                                            val idx = nearestTsIndex(change.position.x, size.width.toFloat(), tsList)
                                            if (idx != lastIdx) {
                                                lastIdx = idx
                                                onTsChange(tsList.getOrNull(idx))
                                            }
                                            touchY = change.position.y
                                        }
                                        touching = false
                                        onTsChange(null)
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    drawSingleZoneChart(
                        samples = samples,
                        lineColor = lineColor,
                        selectedIndex = crosshairIndex,
                        minTs = minTs,
                        durationMs = durationMs,
                    )
                }
                if (showTooltip) {
                    val ts = globalTs ?: return@Box
                    // 非触摸图表用默认纵向位置（避免 Tooltip 重叠），触摸图表跟随手指
                    val effTouchY = if (touching) touchY else chartHeightPx * 0.4f
                    val chartWidth = (widthPx - ChartLeftPadding - ChartRightPadding).coerceAtLeast(1f)
                    val x = ChartLeftPadding + ((ts - minTs).toFloat() / durationMs) * chartWidth
                    CrosshairTooltip(
                        series = listOf(
                            SeriesData(zoneName, lineColor, samples.map { it.timestampMs to it.effectivePowerUw() / 1_000_000f }),
                        ),
                        index = crosshairIndex ?: 0,
                        relativeSeconds = (ts - minTs) / 1000f,
                        anchorX = x,
                        touchY = effTouchY,
                        chartWidth = widthPx.toFloat(),
                        chartHeight = chartHeightPx,
                        formatter = { v -> uwToW((v * 1_000_000f).toLong()) },
                    )
                }
            }
            // 图表下方数据平均值
            ChartAverages(
                listOf(SeriesData(zoneName, lineColor, samples.map { it.timestampMs to it.effectivePowerUw() / 1_000_000f })),
                { v -> uwToW((v * 1_000_000f).toLong()) },
            )
        }
    }
}

private fun DrawScope.drawSingleZoneChart(
    samples: List<SampleEntity>,
    lineColor: Color,
    selectedIndex: Int?,
    minTs: Long = samples.minOfOrNull { it.timestampMs } ?: 0L,
    durationMs: Long = (samples.maxOfOrNull { it.timestampMs } ?: minTs).let { (it - minTs).coerceAtLeast(1L) },
    yTickCount: Int = 8,
) {
    val left = ChartLeftPadding
    val right = size.width - ChartRightPadding
    val top = 18f
    val bottom = size.height - ChartBottomPadding
    val chartWidth = (right - left).coerceAtLeast(1f)
    val chartHeight = (bottom - top).coerceAtLeast(1f)
    val axisColor = Color(0xFF465160)
    val gridColor = Color(0xFF2B3442)
    val labelColor = android.graphics.Color.rgb(190, 203, 214)
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = 22f
    }

    drawLine(axisColor, Offset(left, top), Offset(left, bottom), strokeWidth = 2f)
    drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 2f)

    val maxPower = max(1L, samples.maxOfOrNull { it.effectivePowerUw() } ?: 1L)
    val maxTs = samples.maxOfOrNull { it.timestampMs } ?: minTs

    // 纵坐标分度：整步长刻度（默认 8 条，更密更易读）
    niceTicks(maxPower.toFloat(), yTickCount).forEach { value ->
        val y = bottom - (value / maxPower) * chartHeight
        drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
        drawContext.canvas.nativeCanvas.drawText(uwToW(value.toLong()), 0f, y + 8f, textPaint)
    }
    repeat(5) { i ->
        val ratio = i / 4f
        val x = left + ratio * chartWidth
        drawLine(gridColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
        drawContext.canvas.nativeCanvas.drawText("${((durationMs * ratio) / 1000f).roundToInt()}s", x - 18f, size.height - 8f, textPaint)
    }

    if (samples.isEmpty()) return

    val path = Path()
    samples.forEachIndexed { index, sample ->
        val x = left + ((sample.timestampMs - minTs).toFloat() / durationMs) * chartWidth
        val y = bottom - (sample.effectivePowerUw().toFloat() / maxPower) * chartHeight
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))

    selectedIndex?.let { index ->
        val sample = samples.getOrNull(index) ?: return@let
        val x = left + ((sample.timestampMs - minTs).toFloat() / durationMs) * chartWidth
        val y = bottom - (sample.effectivePowerUw().toFloat() / maxPower) * chartHeight
        drawLine(Color(0xCCFFFFFF), Offset(x, top), Offset(x, bottom), strokeWidth = 1.5f)
        drawLine(Color(0xCCFFFFFF), Offset(left, y), Offset(right, y), strokeWidth = 1.5f)
        drawCircle(lineColor, 7f, Offset(x, y))
        drawContext.canvas.nativeCanvas.drawText(
            "${timeLabel(sample.timestampMs)}  ${uwToW(sample.effectivePowerUw())}  ${ujToJ(sample.energyUj)}",
            left + 8f,
            top + 30f,
            textPaint,
        )
    }
}

private fun nearestSampleIndex(samples: List<SampleEntity>, touchX: Float, width: Float, left: Float, rightPadding: Float): Int? {
    if (samples.isEmpty()) return null
    val chartWidth = (width - left - rightPadding).coerceAtLeast(1f)
    val ratio = ((touchX - left) / chartWidth).coerceIn(0f, 1f)
    return min(samples.lastIndex, max(0, (ratio * samples.lastIndex).roundToInt()))
}

private fun SampleEntity.effectivePowerUw(): Long = powerUw ?: computedPowerUw ?: 0L
private fun uwToW(uw: Long): String = "%.2f W".format(Locale.US, uw / 1_000_000.0)
private fun ujToJ(uj: Long): String = if (uj >= 1_000_000) "%.3f J".format(Locale.US, uj / 1_000_000.0) else "${uj} µJ"
private fun formatDate(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
private fun timeLabel(ms: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ms))
private fun duration(record: RecordEntity): String {
    val end = record.endedAtMs ?: System.currentTimeMillis()
    val seconds = ((end - record.startedAtMs) / 1000).coerceAtLeast(0)
    return "${seconds / 60}m ${seconds % 60}s"
}

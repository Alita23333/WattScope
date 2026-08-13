package com.qpt.powermonitor.core.qpt

import com.qpt.powermonitor.core.root.RootManager
import com.qpt.powermonitor.domain.PowerZone
import com.qpt.powermonitor.domain.ZoneSample
import kotlin.math.max

class QptPowerReader(
    private val rootManager: RootManager,
    private val qptRoot: String = "/sys/class/powercap/qpt",
) {
    private val lastEnergyByPath = mutableMapOf<String, Pair<Long, Long>>()

    suspend fun hasRoot(): Boolean = rootManager.hasRoot()

    suspend fun isAvailable(): Boolean =
        rootManager.execute("[ -d '$qptRoot/' ] && echo 1 || echo 0").firstOrNull()?.trim() == "1"

    suspend fun isEnabled(): Boolean = rootManager.readFile("$qptRoot/enabled")?.trim() == "1"

    suspend fun setEnabled(enabled: Boolean): Boolean =
        rootManager.writeFile("$qptRoot/enabled", if (enabled) "1" else "0")

    suspend fun scanZones(): List<PowerZone> {
        val scanRoot = qptRoot.trimEnd('/') + "/"
        val paths = rootManager.execute(
            "find '$scanRoot' -mindepth 2 -type f -name energy_uj 2>/dev/null | sed 's#/energy_uj\$##' | sort",
        ).mapNotNull { it.trim().takeIf { p -> p.isNotEmpty() } }
        if (paths.isEmpty()) return emptyList()
        // 单条命令批量读取所有 zone 的 name 与 power_uw 可用性，避免逐 zone su 往返
        val cmd = paths.joinToString("; ") {
            "n=\$(cat '${it}/name' 2>/dev/null); [ -f '${it}/power_uw' ] && pw=1 || pw=0; echo \"${it}|\$n|\$pw\""
        }
        return rootManager.execute(cmd).mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 3) return@mapNotNull null
            val name = parts[1].trim()
            if (name.isBlank()) return@mapNotNull null
            PowerZone(path = parts[0], name = name, hasPowerNode = parts[2].trim() == "1")
        }
    }

    /** 构建批量读取全部 zone power_uw/energy_uj 的命令（可与附加监控命令合并为一次 su 往返）。 */
    fun buildSampleCommand(zones: List<PowerZone>): String =
        if (zones.isEmpty()) {
            ""
        } else {
            "for z in ${zones.joinToString(" ") { "'${it.path}'" }}; do " +
                "e=\$(cat \"\$z/energy_uj\" 2>/dev/null); " +
                "p=\$(cat \"\$z/power_uw\" 2>/dev/null); " +
                "echo \"QPT|\$z|\$p|\$e\"; done"
        }

    fun parseSampleLines(lines: List<String>, timestampMs: Long): List<ZoneSample> =
        lines.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 4 || parts[0] != "QPT") return@mapNotNull null
            val path = parts[1]
            val power = parts[2].trim().toLongOrNull()
            val energy = parts[3].trim().toLongOrNull() ?: return@mapNotNull null
            val zone = zonesCache[path] ?: return@mapNotNull null
            val previous = lastEnergyByPath[path]
            lastEnergyByPath[path] = energy to timestampMs
            val computed = if (power == null && previous != null) {
                val deltaEnergy = max(0L, energy - previous.first)
                val deltaMs = max(1L, timestampMs - previous.second)
                deltaEnergy * 1_000L / deltaMs
            } else {
                null
            }
            ZoneSample(zone.path, zone.name, timestampMs, energy, power, computed)
        }

    /** 解析时所需的 zone 路径映射（由 scanZones 的结果缓存）。 */
    private var zonesCache: Map<String, PowerZone> = emptyMap()

    fun cacheZones(zones: List<PowerZone>) {
        zonesCache = zones.associateBy { it.path }
    }

    fun resetDeltas() {
        lastEnergyByPath.clear()
    }
}

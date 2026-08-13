package com.qpt.powermonitor.data.repo

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.qpt.powermonitor.core.root.RootManager

data class ForegroundAppInfo(
    val appName: String,
    val packageName: String,
    val iconPackage: String?,
)

/**
 * 前台应用检测。
 * 优先用 root 执行 `dumpsys activity` 解析 topResumedActivity（最可靠，不受 UsageStats 延迟/包可见性影响）；
 * 失败时回退 UsageStats 事件流增量游标（有效窗口无限制，长前台会话不会误判离开）。
 */
class AppInspector(private val context: Context, private val rootManager: RootManager) {
    // 增量事件游标（回退方案）：首次（或进程重启后）全量扫描 24 小时，之后只查询新增事件。
    private var lastResumedPackage: String? = null
    private var lastEventTimeMs: Long = 0L

    /**
     * 是否为当前前台应用。查询结果为"未知"时返回 true，避免长前台会话被误判为离开。
     */
    suspend fun isForeground(packageName: String): Boolean {
        val current = queryTopResumedPackage() ?: queryForegroundPackage()
        return current == null || current == packageName
    }

    /** 记录开始时使用：root 前台查询，回退 UsageStats，再回退应用自身。 */
    suspend fun currentForegroundApp(): ForegroundAppInfo {
        val packageName = queryTopResumedPackage()
            ?: queryForegroundPackage()
            ?: queryLastUsedPackage24h()
            ?: context.packageName
        val packageManager = context.packageManager
        val appName = runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrElse {
            if (packageName == context.packageName) "WattScope" else packageName
        }
        val hasIcon = runCatching {
            packageManager.getApplicationIcon(packageName)
        }.isSuccess

        return ForegroundAppInfo(
            appName = appName,
            packageName = packageName,
            iconPackage = packageName.takeIf { hasIcon },
        )
    }

    /** root 查询当前 topResumedActivity 的包名。 */
    suspend fun queryTopResumedPackage(): String? {
        val out = rootManager.execute(
            "dumpsys activity activities 2>/dev/null | grep -m1 'topResumedActivity='",
        )
        val line = out.firstOrNull() ?: return null
        val m = Regex("u\\d+\\s+([^/\\s]+)/").find(line) ?: return null
        val pkg = m.groupValues[1].takeIf { it.startsWith("com.") || it.startsWith("android.") }
        return pkg
    }

    private fun queryForegroundPackage(): String? {
        val usageStats = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        // 首次全量 24h；之后增量查询（含 5s 重叠，避免边界事件遗漏）
        val start = if (lastEventTimeMs > 0) lastEventTimeMs - 5_000 else now - 24 * 60 * 60_000L
        val events = usageStats.queryEvents(start, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp > lastEventTimeMs) {
                lastEventTimeMs = event.timeStamp
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastResumedPackage = event.packageName
                }
            }
        }
        return lastResumedPackage
    }

    private fun queryLastUsedPackage24h(): String? {
        val usageStats = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        return usageStats
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24 * 60 * 60_000L, now)
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }
}

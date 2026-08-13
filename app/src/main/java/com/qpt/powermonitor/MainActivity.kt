package com.qpt.powermonitor

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.qpt.powermonitor.service.QptMonitorService
import com.qpt.powermonitor.ui.QptApp
import com.qpt.powermonitor.ui.QptViewModel
import com.qpt.powermonitor.ui.theme.QptTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // 待写入的导出内容（先选保存位置，再异步生成写入）
    private var pendingExport: Pair<Long, Boolean>? = null
    private val createJsonDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { writeExport(it) }
    }
    private val createCsvDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { writeExport(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        startMonitorService()

        val repository = (application as QptApplication).repository
        setContent {
            QptTheme {
                QptApp(
                    viewModel = QptViewModel(repository),
                    onRequestOverlay = { requestOverlayPermission() },
                    onStartService = { startMonitorService() },
                    onRequestBatteryWhitelist = { requestIgnoreBatteryOptimizations() },
                    onOpenMiuiAutostart = { openMiuiAutostartSettings() },
                    onToggleBubble = { enabled ->
                        repository.bubbleEnabled = enabled
                        val action = if (enabled) QptMonitorService.ACTION_SHOW_BUBBLE else QptMonitorService.ACTION_HIDE_BUBBLE
                        // 注意：必须 startService（此前误用 startActivity 导致 ActivityNotFoundException 闪退）
                        startService(Intent(this, QptMonitorService::class.java).setAction(action))
                    },
                    onExportRecord = { recordId, asJson -> startExport(recordId, asJson) },
                )
            }
        }
    }

    private fun startExport(recordId: Long, asJson: Boolean) {
        pendingExport = recordId to asJson
        val suggestedName = "qpt_record_${recordId}_${System.currentTimeMillis()}.${if (asJson) "json" else "csv"}"
        if (asJson) createJsonDoc.launch(suggestedName) else createCsvDoc.launch(suggestedName)
    }

    private fun writeExport(uri: Uri) {
        val (recordId, asJson) = pendingExport ?: return
        pendingExport = null
        val repository = (application as QptApplication).repository
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val content = if (asJson) repository.exportRecordJson(recordId) else repository.exportRecordCsv(recordId)
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray())
                    } ?: error("无法打开输出流")
                }
            }
            Toast.makeText(
                this@MainActivity,
                if (result.isSuccess) "导出成功" else "导出失败：${result.exceptionOrNull()?.message}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun requestIgnoreBatteryOptimizations() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun openMiuiAutostartSettings() {
        runCatching {
            startActivity(
                Intent()
                    .setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun startMonitorService() {
        ContextCompat.startForegroundService(this, Intent(this, QptMonitorService::class.java))
    }
}

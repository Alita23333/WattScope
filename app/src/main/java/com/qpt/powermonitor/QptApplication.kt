package com.qpt.powermonitor

import android.app.Application
import com.qpt.powermonitor.core.auxiliary.AuxReader
import com.qpt.powermonitor.core.qpt.QptPowerReader
import com.qpt.powermonitor.core.root.RootManager
import com.qpt.powermonitor.data.local.QptDatabase
import com.qpt.powermonitor.data.repo.MonitorRepository

class QptApplication : Application() {
    lateinit var repository: MonitorRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database = QptDatabase.create(this)
        val rootManager = RootManager()
        repository = MonitorRepository(
            context = this,
            database = database,
            recordDao = database.recordDao(),
            sampleDao = database.sampleDao(),
            auxSampleDao = database.auxSampleDao(),
            qptReader = QptPowerReader(rootManager),
            auxReader = AuxReader(rootManager),
            rootManager = rootManager,
        )
    }
}

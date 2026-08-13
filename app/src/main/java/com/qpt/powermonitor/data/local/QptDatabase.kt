package com.qpt.powermonitor.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecordEntity::class,
        SampleEntity::class,
        ClusterFreqEntity::class,
        CoreUsageEntity::class,
        TempEntity::class,
        DevicePowerEntity::class,
        GpuEntity::class,
        DdrFreqEntity::class,
        FpsEntity::class,
    ],
    version = 4,
)
abstract class QptDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao
    abstract fun sampleDao(): SampleDao
    abstract fun auxSampleDao(): AuxSampleDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fps_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordId` INTEGER NOT NULL, " +
                        "`timestampMs` INTEGER NOT NULL, " +
                        "`fps` REAL NOT NULL, " +
                        "FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fps_samples_recordId` ON `fps_samples` (`recordId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fps_samples_timestampMs` ON `fps_samples` (`timestampMs`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gpu_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordId` INTEGER NOT NULL, " +
                        "`timestampMs` INTEGER NOT NULL, " +
                        "`freqHz` INTEGER NOT NULL, " +
                        "`usagePercent` REAL, " +
                        "FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gpu_samples_recordId` ON `gpu_samples` (`recordId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gpu_samples_timestampMs` ON `gpu_samples` (`timestampMs`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ddr_freq_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordId` INTEGER NOT NULL, " +
                        "`timestampMs` INTEGER NOT NULL, " +
                        "`freqHz` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ddr_freq_samples_recordId` ON `ddr_freq_samples` (`recordId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ddr_freq_samples_timestampMs` ON `ddr_freq_samples` (`timestampMs`)")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cluster_freq_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordId` INTEGER NOT NULL, " +
                        "`timestampMs` INTEGER NOT NULL, " +
                        "`clusterName` TEXT NOT NULL, " +
                        "`freqKhz` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cluster_freq_samples_recordId` ON `cluster_freq_samples` (`recordId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cluster_freq_samples_clusterName` ON `cluster_freq_samples` (`clusterName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cluster_freq_samples_timestampMs` ON `cluster_freq_samples` (`timestampMs`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `core_usage_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordId` INTEGER NOT NULL, " +
                        "`timestampMs` INTEGER NOT NULL, " +
                        "`coreIndex` INTEGER NOT NULL, " +
                        "`usagePercent` REAL NOT NULL, " +
                        "FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_core_usage_samples_recordId` ON `core_usage_samples` (`recordId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_core_usage_samples_coreIndex` ON `core_usage_samples` (`coreIndex`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_core_usage_samples_timestampMs` ON `core_usage_samples` (`timestampMs`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `temp_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordId` INTEGER NOT NULL, " +
                        "`timestampMs` INTEGER NOT NULL, " +
                        "`sensor` TEXT NOT NULL, " +
                        "`tempC` REAL NOT NULL, " +
                        "FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_temp_samples_recordId` ON `temp_samples` (`recordId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_temp_samples_sensor` ON `temp_samples` (`sensor`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_temp_samples_timestampMs` ON `temp_samples` (`timestampMs`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `device_power_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordId` INTEGER NOT NULL, " +
                        "`timestampMs` INTEGER NOT NULL, " +
                        "`powerUw` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_device_power_samples_recordId` ON `device_power_samples` (`recordId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_device_power_samples_timestampMs` ON `device_power_samples` (`timestampMs`)")
            }
        }

        fun create(context: Context): QptDatabase = Room.databaseBuilder(
            context,
            QptDatabase::class.java,
            "qpt-power-monitor.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}

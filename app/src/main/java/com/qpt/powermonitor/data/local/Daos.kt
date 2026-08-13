package com.qpt.powermonitor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Insert
    suspend fun insert(record: RecordEntity): Long

    @Update
    suspend fun update(record: RecordEntity)

    @Query("SELECT * FROM records ORDER BY startedAtMs DESC")
    fun observeRecords(): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE id = :id")
    fun observeRecord(id: Long): Flow<RecordEntity?>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getRecord(id: Long): RecordEntity?

    @Query("SELECT * FROM records WHERE endedAtMs IS NULL ORDER BY startedAtMs ASC")
    suspend fun getOpenRecords(): List<RecordEntity>

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM records")
    suspend fun deleteAll()
}

@Dao
interface SampleDao {
    @Insert
    suspend fun insertAll(samples: List<SampleEntity>)

    @Query("SELECT * FROM samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    fun observeSamples(recordId: Long): Flow<List<SampleEntity>>

    @Query("SELECT * FROM samples WHERE recordId = :recordId")
    suspend fun getSamples(recordId: Long): List<SampleEntity>
}

@Dao
interface AuxSampleDao {
    @Insert
    suspend fun insertClusterFreqs(samples: List<ClusterFreqEntity>)

    @Insert
    suspend fun insertCoreUsages(samples: List<CoreUsageEntity>)

    @Insert
    suspend fun insertTemps(samples: List<TempEntity>)

    @Insert
    suspend fun insertDevicePowers(samples: List<DevicePowerEntity>)

    @Insert
    suspend fun insertGpus(samples: List<GpuEntity>)

    @Insert
    suspend fun insertDdrFreqs(samples: List<DdrFreqEntity>)

    @Insert
    suspend fun insertFps(samples: List<FpsEntity>)

    @Query("SELECT * FROM cluster_freq_samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    fun observeClusterFreqs(recordId: Long): Flow<List<ClusterFreqEntity>>

    @Query("SELECT * FROM core_usage_samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    fun observeCoreUsages(recordId: Long): Flow<List<CoreUsageEntity>>

    @Query("SELECT * FROM temp_samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    fun observeTemps(recordId: Long): Flow<List<TempEntity>>

    @Query("SELECT * FROM device_power_samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    fun observeDevicePowers(recordId: Long): Flow<List<DevicePowerEntity>>

    @Query("SELECT * FROM gpu_samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    fun observeGpus(recordId: Long): Flow<List<GpuEntity>>

    @Query("SELECT * FROM ddr_freq_samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    fun observeDdrFreqs(recordId: Long): Flow<List<DdrFreqEntity>>

    @Query("SELECT * FROM fps_samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    fun observeFps(recordId: Long): Flow<List<FpsEntity>>

    @Query("SELECT * FROM fps_samples WHERE recordId = :recordId")
    suspend fun getFps(recordId: Long): List<FpsEntity>

    @Query("SELECT * FROM cluster_freq_samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    suspend fun getClusterFreqs(recordId: Long): List<ClusterFreqEntity>

    @Query("SELECT * FROM core_usage_samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    suspend fun getCoreUsages(recordId: Long): List<CoreUsageEntity>

    @Query("SELECT * FROM temp_samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    suspend fun getTemps(recordId: Long): List<TempEntity>

    @Query("SELECT * FROM device_power_samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    suspend fun getDevicePowers(recordId: Long): List<DevicePowerEntity>

    @Query("SELECT * FROM gpu_samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    suspend fun getGpus(recordId: Long): List<GpuEntity>

    @Query("SELECT * FROM ddr_freq_samples WHERE recordId = :recordId ORDER BY timestampMs ASC")
    suspend fun getDdrFreqs(recordId: Long): List<DdrFreqEntity>
}

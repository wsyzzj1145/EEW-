package com.example.data.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "home_settings")
data class DbHomeSetting(
    @PrimaryKey val id: Int = 1, // Only 1 home setting row
    val latitude: Double = 30.67, // Default: Chengdu
    val longitude: Double = 104.06,
    val name: String = "四川省成都市中心",
    val alertThreshold: Double = 0.0, // Alert if local pre-estimated intensity > this threshold
    val isSystemAlertEnabled: Boolean = true, // Force floating window popup if permitted
    val soundEnabled: Boolean = true,
    val playTtsEnabled: Boolean = true
)

@Entity(tableName = "earthquake_records")
data class DbEarthquakeRecord(
    @PrimaryKey val eventId: String,
    val time: String,
    val reportTime: String,
    val placeName: String,
    val magnitude: Double,
    val depth: Double,
    val latitude: Double,
    val longitude: Double,
    val intensity: String,
    val infoTypeName: String,
    val isRealTime: Boolean
)

@Dao
interface EarthquakeDao {
    @Query("SELECT * FROM home_settings LIMIT 1")
    fun getHomeSettingFlow(): Flow<DbHomeSetting?>

    @Query("SELECT * FROM home_settings LIMIT 1")
    suspend fun getHomeSettingDirect(): DbHomeSetting?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHomeSetting(settings: DbHomeSetting)

    @Query("SELECT * FROM earthquake_records ORDER BY time DESC LIMIT 100")
    fun getCachedEarthquakesFlow(): Flow<List<DbEarthquakeRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEarthquakes(records: List<DbEarthquakeRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSingleEarthquake(record: DbEarthquakeRecord)

    @Query("DELETE FROM earthquake_records")
    suspend fun clearCachedEarthquakes()
}

@Database(entities = [DbHomeSetting::class, DbEarthquakeRecord::class], version = 1, exportSchema = false)
abstract class EarthquakeDatabase : RoomDatabase() {
    abstract fun dao(): EarthquakeDao

    companion object {
        @Volatile
        private var INSTANCE: EarthquakeDatabase? = null

        fun getInstance(context: Context): EarthquakeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EarthquakeDatabase::class.java,
                    "earthquake_guardian.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

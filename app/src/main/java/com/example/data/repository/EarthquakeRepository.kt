package com.example.data.repository

import com.example.data.db.DbEarthquakeRecord
import com.example.data.db.DbHomeSetting
import com.example.data.db.EarthquakeDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EarthquakeRepository(private val dao: EarthquakeDao) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Flow streams for UI layers to collect reactively
    val homeSettingFlow: Flow<DbHomeSetting?> = dao.getHomeSettingFlow()
    val cachedEarthquakesFlow: Flow<List<DbEarthquakeRecord>> = dao.getCachedEarthquakesFlow()

    suspend fun getHomeSettingDirect(): DbHomeSetting? {
        return dao.getHomeSettingDirect()
    }

    suspend fun updateHomeSetting(settings: DbHomeSetting) = withContext(Dispatchers.IO) {
        dao.insertHomeSetting(settings)
    }

    suspend fun insertRealtimeRecord(record: DbEarthquakeRecord) = withContext(Dispatchers.IO) {
        dao.insertSingleEarthquake(record)
    }

    /**
     * Fetch recent earthquake catalog from Wolfx API, parse, and store locally in Room.
     */
    suspend fun fetchRecentEarthquakes(): Result<List<DbEarthquakeRecord>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.wolfx.jp/cenc_eqlist.json")
                .header("User-Agent", "Mozilla/5.0 EarthquakeGuardianApp")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP error code: ${response.code}"))
            }

            val bodyText = response.body?.string() ?: ""
            if (bodyText.isEmpty()) {
                return@withContext Result.failure(Exception("Empty response body"))
            }

            val jsonObject = JSONObject(bodyText)
            val list = mutableListOf<DbEarthquakeRecord>()
            val keys = jsonObject.keys()

            while (keys.hasNext()) {
                val key = keys.next()
                if (key.startsWith("No")) {
                    val itemObj = jsonObject.optJSONObject(key) ?: continue
                    val eventId = itemObj.optString("EventID", "")
                    val time = itemObj.optString("time", "")
                    val reportTime = itemObj.optString("ReportTime", "")
                    val location = itemObj.optString("location", "")
                    val magnitudeStr = itemObj.optString("magnitude", "0.0")
                    val depthStr = itemObj.optString("depth", "0.0")
                    val latitudeStr = itemObj.optString("latitude", "0.0")
                    val longitudeStr = itemObj.optString("longitude", "0.0")
                    val intensity = itemObj.optString("intensity", "")
                    val type = itemObj.optString("type", "")

                    val infoType = if (type == "reviewed" || type == "formal") "[正式测定]" else "[自动测定]"

                    if (eventId.isNotEmpty()) {
                        val record = DbEarthquakeRecord(
                            eventId = eventId,
                            time = time,
                            reportTime = reportTime,
                            placeName = location,
                            magnitude = magnitudeStr.toDoubleOrNull() ?: 0.0,
                            depth = depthStr.toDoubleOrNull() ?: 0.0,
                            latitude = latitudeStr.toDoubleOrNull() ?: 0.0,
                            longitude = longitudeStr.toDoubleOrNull() ?: 0.0,
                            intensity = intensity,
                            infoTypeName = infoType,
                            isRealTime = false
                        )
                        list.add(record)
                    }
                }
            }

            list.sortByDescending { it.time }

            if (list.isNotEmpty()) {
                // Insert into local cache database
                dao.insertEarthquakes(list)
            }

            Result.success(list)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}

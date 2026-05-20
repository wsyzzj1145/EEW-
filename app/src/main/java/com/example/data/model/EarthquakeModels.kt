package com.example.data.model

import java.io.Serializable
import kotlin.math.*

/**
 * Historical earthquake event parsed from Wolfx or CENC WebSocket
 */
data class EarthquakeRecord(
    val eventId: String,
    val time: String,
    val reportTime: String,
    val placeName: String,
    val magnitude: Double,
    val depth: Double,
    val latitude: Double,
    val longitude: Double,
    val intensity: String, // Calculated or official intensity, e.g. "5"
    val infoTypeName: String = "[正式测定]",
    val isRealTime: Boolean = false
) : Serializable

/**
 * Earthquake Early Warning (EEW) representing real-time alarm events (from CEA)
 */
data class EarlyWarningEvent(
    val id: String,
    val eventId: String,
    val shockTime: String,
    val longitude: Double,
    val latitude: Double,
    val placeName: String,
    val magnitude: Double,
    val depth: Double,
    val epiIntensity: Double, // Intensity at Epicenter
    val updates: Int,
    val isMock: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable {
    
    /**
     * Compute distance from user's local coordinate to the epicenter in kilometers.
     */
    fun calculateDistanceKm(userLat: Double, userLon: Double): Double {
        val earthRadius = 6371.0 // kilometers
        val dLat = Math.toRadians(latitude - userLat)
        val dLon = Math.toRadians(longitude - userLon)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(userLat)) * cos(Math.toRadians(latitude)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * Empirical seismic intensity attenuation formula in China
     * Returns the pre-calculated local intensity degree (0.0 to 12.0)
     */
    fun estimateLocalIntensity(userLat: Double, userLon: Double): Double {
        val distance = calculateDistanceKm(userLat, userLon)
        if (distance < 3.0) {
            return maxOf(0.0, 1.5 * magnitude - 0.5)
        }
        // Attenuation relation: I = 1.5 * M - 1.5 * log10(d) - 0.5
        val est = 1.5 * magnitude - 1.5 * log10(distance) - 0.5
        return maxOf(0.0, est)
    }

    /**
     * Calculate seismic S-Wave arrival remaining countdown seconds.
     * S-waves travel slowly (approx 4.0 km/s).
     * Elapsed time is difference between current system time and earthquake occurrence time.
     */
    fun getSWaveCountdownSeconds(userLat: Double, userLon: Double): Int {
        val distance = calculateDistanceKm(userLat, userLon)
        // S-wave average speed is ~4.0 km/s
        val travelTimeSeconds = (distance / 4.0).toInt()
        
        // Parse shockTime to get elapsed seconds
        // format: "YYYY-MM-DD HH:mm:ss"
        val elapsedSeconds = try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val date = sdf.parse(shockTime)
            if (date != null) {
                ((System.currentTimeMillis() - date.time) / 1000).toInt()
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
        
        val countdown = travelTimeSeconds - elapsedSeconds
        return if (countdown < 0) 0 else countdown
    }
}

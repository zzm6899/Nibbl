package com.foodtracker.diary.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

data class DiaryLocation(
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
)

class LocationHelper(private val context: Context) {
    @SuppressLint("MissingPermission")
    suspend fun currentCafeHint(): DiaryLocation {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return DiaryLocation("", null, null)

        val location = suspendCancellableCoroutine<android.location.Location?> { continuation ->
            LocationServices.getFusedLocationProviderClient(context).lastLocation
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
        } ?: return DiaryLocation("", null, null)

        val name = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { listOfNotNull(it.featureName, it.thoroughfare, it.locality).distinct().joinToString(", ") }
                .orEmpty()
        }.getOrDefault("")

        return DiaryLocation(name, location.latitude, location.longitude)
    }
}

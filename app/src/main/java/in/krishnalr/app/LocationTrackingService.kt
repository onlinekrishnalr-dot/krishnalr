package `in`.krishnalr.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class LocationTrackingService : Service() {

    companion object {
        const val ACTION_START = "in.krishnalr.app.START_TRACKING"
        const val ACTION_STOP = "in.krishnalr.app.STOP_TRACKING"

        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_DEVICE_TOKEN = "device_token"
        const val EXTRA_SUPABASE_URL = "supabase_url"
        const val EXTRA_SUPABASE_ANON_KEY = "supabase_anon_key"

        private const val CHANNEL_ID = "krishna_lr_tracking"
        private const val NOTIFICATION_ID = 7301
        private const val TAG = "KRISHNA_LR_TRACKING"
        private const val INTERVAL_MS = 30_000L
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private val executor = Executors.newSingleThreadExecutor()

    private var tripId: String? = null
    private var deviceToken: String? = null
    private var supabaseUrl: String? = null
    private var anonKey: String? = null

    private var locationCallback: LocationCallback? = null
    private var trackingStarted = false

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                tripId = intent.getStringExtra(EXTRA_TRIP_ID)
                deviceToken = intent.getStringExtra(EXTRA_DEVICE_TOKEN)
                supabaseUrl = intent.getStringExtra(EXTRA_SUPABASE_URL)
                anonKey = intent.getStringExtra(EXTRA_SUPABASE_ANON_KEY)

                if (!isConfigurationValid()) {
                    Log.e(TAG, "Missing or invalid tracking configuration")
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (!hasLocationPermission()) {
                    Log.e(TAG, "Location permission not granted")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startTracking()
            }
        }

        return START_NOT_STICKY
    }

    private fun isConfigurationValid(): Boolean {
        return !tripId.isNullOrBlank()
                && !deviceToken.isNullOrBlank()
                && !supabaseUrl.isNullOrBlank()
                && !anonKey.isNullOrBlank()
                && tripId!!.matches(
                    Regex("^[0-9a-fA-F-]{36}$")
                )
                && supabaseUrl!!.startsWith("https://")
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    private fun startTracking() {
        if (trackingStarted) return

        val notification = createNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start foreground service", e)
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(15_000L)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return

                sendLocation(
                    location.latitude,
                    location.longitude
                )
            }
        }

        try {
            fusedClient.requestLocationUpdates(
                request,
                locationCallback!!,
                mainLooper
            )
            trackingStarted = true
            Log.d(TAG, "Location tracking started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission error", e)
            stopTracking()
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Location updates failed", e)
            stopTracking()
            stopSelf()
        }
    }

    private fun sendLocation(lat: Double, lng: Double) {
        val currentTripId = tripId ?: return
        val currentToken = deviceToken ?: return
        val baseUrl = supabaseUrl?.trimEnd('/') ?: return
        val key = anonKey ?: return

        executor.execute {
            var connection: HttpURLConnection? = null

            try {
                val endpoint = "$baseUrl/rest/v1/rpc/update_trip_location"

                connection = (URL(endpoint).openConnection()
                        as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("apikey", key)
                    setRequestProperty("Authorization", "Bearer $key")
                }

                val body = JSONObject().apply {
                    put("p_trip_id", currentTripId)
                    put("p_lat", lat)
                    put("p_lng", lng)
                    put("p_device_token", currentToken)
                }

                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val response = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use {
                        it.readText()
                    }.orEmpty()
                }

                Log.d(TAG, "Location API response: $responseCode")

                if (response.contains("DEVICE_MISMATCH", ignoreCase = true)) {
                    Log.e(TAG, "Device token mismatch. Stopping tracking.")
                    stopOnMainThread()
                    return@execute
                }

                if (responseCode in 200..299 &&
                    response.trim().equals("false", ignoreCase = true)
                ) {
                    Log.d(TAG, "Trip is no longer ongoing. Stopping tracking.")
                    stopOnMainThread()
                }

                if (responseCode !in 200..299) {
                    Log.e(TAG, "Location API error: $response")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Location upload failed", e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun stopOnMainThread() {
        android.os.Handler(mainLooper).post {
            stopTracking()
            stopSelf()
        }
    }

    private fun stopTracking() {
        locationCallback?.let {
            fusedClient.removeLocationUpdates(it)
        }

        locationCallback = null
        trackingStarted = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        Log.d(TAG, "Location tracking stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KRISHNA LR Trip Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ट्रिप के दौरान वाहन की लोकेशन शेयर हो रही है"
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("KRISHNA LR - Trip Tracking")
            .setContentText("आपकी ट्रिप की लोकेशन शेयर हो रही है")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopTracking()
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}


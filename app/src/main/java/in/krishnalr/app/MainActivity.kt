package `in`.krishnalr.app

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var offlinePanel: View

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val filePickerRequest = 4102
    private val locationPermissionRequest = 4103

    private val homeUrl = "https://krishnalr.in/"

    // Supabase project configuration
    private val supabaseUrl =
        "https://wzvimorjlikengcvpumv.supabase.co"

    // Publishable key only. Never put service_role key in Android app.
    private val supabasePublishableKey =
        "sb_publishable_L0CDsssCVASOwCWolNSyfw_N6oswjQ9"

    private var pendingTripId: String? = null
    private var pendingDeviceToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        offlinePanel = findViewById(R.id.offlinePanel)

        findViewById<ImageButton>(R.id.reloadButton)
            .setOnClickListener { webView.reload() }

        findViewById<ImageButton>(R.id.shareButton)
            .setOnClickListener { sharePage() }

        findViewById<MaterialButton>(R.id.retryButton)
            .setOnClickListener { loadHome() }

        configureWebView()
        configureNativeTrackingBridge()

        val incomingUrl = getTrustedIncomingUrl(intent)

        if (incomingUrl != null) {
            webView.loadUrl(incomingUrl)
        } else if (savedInstanceState != null) {
            val restored = webView.restoreState(savedInstanceState)
            if (restored == null) loadHome()
        } else {
            loadHome()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val incomingUrl = getTrustedIncomingUrl(intent)
        if (incomingUrl != null) {
            webView.loadUrl(incomingUrl)
        }
    }

    private fun getTrustedIncomingUrl(intent: Intent?): String? {
        val uri = intent?.data ?: return null

        if (uri.scheme != "https") return null

        val host = uri.host?.lowercase() ?: return null
        if (host != "krishnalr.in" && host != "www.krishnalr.in") {
            return null
        }

        return uri.toString()
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString KrishnaLRAndroid/1.0"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val host = uri.host?.lowercase().orEmpty()

                if (
                    uri.scheme == "https" &&
                    (host == "krishnalr.in" || host == "www.krishnalr.in")
                ) {
                    return false
                }

                if (
                    uri.scheme == "http" ||
                    uri.scheme == "https" ||
                    uri.scheme == "tel" ||
                    uri.scheme == "mailto" ||
                    uri.scheme == "whatsapp"
                ) {
                    openExternal(uri)
                    return true
                }

                return true
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                progress.visibility = View.VISIBLE
                offlinePanel.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                offlinePanel.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) showOffline()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int
            ) {
                progress.progress = newProgress
                progress.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                return try {
                    startActivityForResult(
                        params.createIntent(),
                        filePickerRequest
                    )
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    Toast.makeText(
                        this@MainActivity,
                        "फाइल चुनने के लिए ऐप उपलब्ध नहीं है",
                        Toast.LENGTH_SHORT
                    ).show()
                    false
                }
            }
        }

        webView.setDownloadListener(
            DownloadListener {
                    url, userAgent, contentDisposition, mimeType, _ ->

                try {
                    val request = DownloadManager.Request(Uri.parse(url))
                    val fileName = URLUtil.guessFileName(
                        url,
                        contentDisposition,
                        mimeType
                    )

                    request.setTitle(fileName)
                    request.setDescription("KRISHNA LR डाउनलोड")
                    request.setMimeType(mimeType)
                    request.addRequestHeader("User-Agent", userAgent)

                    CookieManager.getInstance()
                        .getCookie(url)
                        ?.let { request.addRequestHeader("Cookie", it) }

                    request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )

                    request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )

                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager)
                        .enqueue(request)

                    Toast.makeText(
                        this,
                        "डाउनलोड शुरू हो गया है",
                        Toast.LENGTH_SHORT
                    ).show()

                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "डाउनलोड शुरू नहीं हो सका",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    /*
     * Secure bridge:
     * Only HTTPS messages from the main frame of our own website
     * are accepted. Website sends JSON messages using
     * window.KrishnaLRBridge.postMessage(...)
     */
    private fun configureNativeTrackingBridge() {

        if (!WebViewFeature.isFeatureSupported(
                WebViewFeature.WEB_MESSAGE_LISTENER
            )
        ) {
            Toast.makeText(
                this,
                "इस Android System WebView में सुरक्षित Tracking Bridge उपलब्ध नहीं है",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        WebViewCompat.addWebMessageListener(
            webView,
            "KrishnaLRBridge",
            setOf(
                "https://krishnalr.in",
                "https://www.krishnalr.in"
            )
        ) { _, message, sourceOrigin, isMainFrame, _ ->

            if (!isMainFrame) return@addWebMessageListener
            if (sourceOrigin.scheme != "https") return@addWebMessageListener

            val host = sourceOrigin.host?.lowercase()
            if (host != "krishnalr.in" && host != "www.krishnalr.in") {
                return@addWebMessageListener
            }

            handleWebsiteTrackingMessage(message.data)
        }
    }

    private fun handleWebsiteTrackingMessage(data: String?) {
        if (data.isNullOrBlank() || data.length > 4096) return

        try {
            val json = JSONObject(data)
            when (json.optString("action")) {

                "start" -> {
                    val tripId = json.optString("tripId")
                    val deviceToken = json.optString("deviceToken")

                    if (!isValidTripId(tripId)) {
                        LogMessage("Invalid trip ID received")
                        return
                    }

                    if (
                        deviceToken.isBlank() ||
                        deviceToken.length > 512
                    ) {
                        LogMessage("Invalid device token received")
                        return
                    }

                    runOnUiThread {
                        requestTrackingPermissionsAndStart(
                            tripId,
                            deviceToken
                        )
                    }
                }

                "stop" -> {
                    runOnUiThread {
                        stopNativeTracking()
                    }
                }
            }
        } catch (e: Exception) {
            LogMessage("Invalid tracking message")
        }
    }

    private fun isValidTripId(value: String): Boolean {
        return value.matches(
            Regex(
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
            )
        )
    }

    private fun requestTrackingPermissionsAndStart(
        tripId: String,
        deviceToken: String
    ) {
        pendingTripId = tripId
        pendingDeviceToken = deviceToken

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(
                missing.toTypedArray(),
                locationPermissionRequest
            )
        } else {
            startNativeTracking()
        }
    }

    @Deprecated("Use Activity Result API in a future update")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == locationPermissionRequest) {
            val locationGranted =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

            if (locationGranted) {
                startNativeTracking()
            } else {
                pendingTripId = null
                pendingDeviceToken = null

                Toast.makeText(
                    this,
                    "ट्रिप ट्रैकिंग के लिए Location Permission जरूरी है",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startNativeTracking() {
        val tripId = pendingTripId ?: return
        val deviceToken = pendingDeviceToken ?: return

        if (!isValidTripId(tripId) || deviceToken.isBlank()) return

        val serviceIntent = Intent(
            this,
            LocationTrackingService::class.java
        ).apply {
            action = LocationTrackingService.ACTION_START
            putExtra(LocationTrackingService.EXTRA_TRIP_ID, tripId)
            putExtra(LocationTrackingService.EXTRA_DEVICE_TOKEN, deviceToken)
            putExtra(LocationTrackingService.EXTRA_SUPABASE_URL, supabaseUrl)
            putExtra(
                LocationTrackingService.EXTRA_SUPABASE_ANON_KEY,
                supabasePublishableKey
            )
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(
                this,
                "KRISHNA LR: ट्रिप ट्रैकिंग शुरू की जा रही है",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Tracking शुरू नहीं हो सकी: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        } finally {
            pendingTripId = null
            pendingDeviceToken = null
        }
    }

    private fun stopNativeTracking() {
        val stopIntent = Intent(
            this,
            LocationTrackingService::class.java
        ).apply {
            action = LocationTrackingService.ACTION_STOP
        }

        try {
            startService(stopIntent)
        } catch (e: Exception) {
            LogMessage("Could not stop tracking service")
        }
    }

    private fun loadHome() {
        if (isOnline()) {
            offlinePanel.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(homeUrl)
        } else {
            showOffline()
        }
    }

    private fun showOffline() {
        progress.visibility = View.GONE
        webView.visibility = View.GONE
        offlinePanel.visibility = View.VISIBLE
    }

    private fun isOnline(): Boolean {
        val manager =
            getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager

        val network = manager.activeNetwork ?: return false
        val capabilities =
            manager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(
            android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
        )
    }

    private fun sharePage() {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "KRISHNA LR")
            putExtra(Intent.EXTRA_TEXT, webView.url ?: homeUrl)
        }

        startActivity(
            Intent.createChooser(share, "KRISHNA LR शेयर करें")
        )
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "इस लिंक को खोलने के लिए ऐप उपलब्ध नहीं है",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun LogMessage(message: String) {
        android.util.Log.e("KRISHNA_LR_APP", message)
    }

    @Deprecated("Deprecated for WebView file chooser compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == filePickerRequest) {
            val result =
                if (resultCode == Activity.RESULT_OK) {
                    WebChromeClient.FileChooserParams.parseResult(
                        resultCode,
                        data
                    )
                } else {
                    null
                }

            filePathCallback?.onReceiveValue(result)
            filePathCallback = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null

        webView.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }

        super.onDestroy()
    }
}

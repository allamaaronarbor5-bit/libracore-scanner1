package com.libracore.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var previewView:     PreviewView
    private lateinit var etServerUrl:     EditText
    private lateinit var etSessionCode:   EditText
    private lateinit var btnConnect:      Button
    private lateinit var tvStatus:        TextView
    private lateinit var tvLastScan:      TextView
    private lateinit var scanOverlay:     View
    private lateinit var connectPanel:    View
    private lateinit var cameraPanel:     View
    private lateinit var btnDisconnect:   Button

    // ── Camera / ML Kit ───────────────────────────────────────────────────────
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalyzer:           ImageAnalysis? = null
    private val scanner = BarcodeScanning.getClient()
    private var lastScannedData = ""
    private var cooldownActive  = false         // 2-second pause between scans

    // ── WebSocket ─────────────────────────────────────────────────────────────
    private var webSocket:  WebSocket? = null
    private val client =    OkHttpClient()
    private var connected = false

    companion object {
        private const val TAG              = "LibraCoreScanner"
        private const val CAMERA_REQUEST   = 100
        private const val SCAN_COOLDOWN_MS = 2000L
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView   = findViewById(R.id.previewView)
        etServerUrl   = findViewById(R.id.etServerUrl)
        etSessionCode = findViewById(R.id.etSessionCode)
        btnConnect    = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvStatus      = findViewById(R.id.tvStatus)
        tvLastScan    = findViewById(R.id.tvLastScan)
        scanOverlay   = findViewById(R.id.scanOverlay)
        connectPanel  = findViewById(R.id.connectPanel)
        cameraPanel   = findViewById(R.id.cameraPanel)

        cameraExecutor = Executors.newSingleThreadExecutor()

        btnConnect.setOnClickListener    { attemptConnect() }
        btnDisconnect.setOnClickListener { disconnectWS() }

        requestCameraPermission()
    }

    // ── Permissions ───────────────────────────────────────────────────────────
    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST
            )
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        req: Int, perms: Array<String>, res: IntArray
    ) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == CAMERA_REQUEST && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            setStatus("Camera permission denied", false)
        }
    }

    // ── Camera / QR scanning ─────────────────────────────────────────────────
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        if (cooldownActive || !connected) {
            imageProxy.close(); return
        }
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    if (barcode.format == Barcode.FORMAT_QR_CODE) {
                        val raw = barcode.rawValue ?: continue
                        if (raw != lastScannedData) {
                            lastScannedData = raw
                            onQrScanned(raw)
                            startCooldown()
                            break
                        }
                    }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun startCooldown() {
        cooldownActive = true
        Handler(Looper.getMainLooper()).postDelayed({
            cooldownActive    = false
            lastScannedData   = ""
        }, SCAN_COOLDOWN_MS)
    }

    // ── QR scanned callback ───────────────────────────────────────────────────
    private fun onQrScanned(data: String) {
        Log.d(TAG, "QR scanned: $data")
        runOnUiThread {
            tvLastScan.text    = "Last: $data"
            tvLastScan.visibility = View.VISIBLE
            flashOverlay()
        }
        sendScan(data)
    }

    private fun flashOverlay() {
        scanOverlay.animate().alpha(0.6f).setDuration(80).withEndAction {
            scanOverlay.animate().alpha(0f).setDuration(200).start()
        }.start()
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────
    private fun attemptConnect() {
        val serverUrl = etServerUrl.text.toString().trim()
        val code      = etSessionCode.text.toString().trim().uppercase()

        if (serverUrl.isEmpty()) { toast("Enter server URL"); return }
        if (code.length < 4)    { toast("Enter session code from PC"); return }

        setStatus("Connecting…", false)
        btnConnect.isEnabled = false

        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WS open")
                ws.send(JSONObject().apply {
                    put("type", "connect_phone")
                    put("code", code)
                }.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "connected" -> runOnUiThread {
                        connected = true
                        setStatus("✅ Connected — scan a book QR", true)
                        showCameraPanel()
                        toast("Connected to PC session!")
                    }
                    "error" -> runOnUiThread {
                        val reason = msg.optString("reason", "unknown")
                        setStatus("Error: $reason", false)
                        btnConnect.isEnabled = true
                        toast("Connection error: $reason")
                    }
                    "scan_ack" -> Log.d(TAG, "Scan acknowledged by PC")
                    "pc_disconnected" -> runOnUiThread {
                        connected = false
                        setStatus("PC disconnected", false)
                        toast("PC session ended")
                        showConnectPanel()
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure", t)
                runOnUiThread {
                    connected = false
                    setStatus("Connection failed: ${t.localizedMessage}", false)
                    btnConnect.isEnabled = true
                    showConnectPanel()
                    toast("Cannot reach server — check URL and network")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    connected = false
                    setStatus("Disconnected", false)
                    btnConnect.isEnabled = true
                    showConnectPanel()
                }
            }
        })
    }

    private fun disconnectWS() {
        webSocket?.close(1000, "User disconnected")
        webSocket  = null
        connected  = false
        setStatus("Disconnected", false)
        showConnectPanel()
    }

    private fun sendScan(data: String) {
        val ws = webSocket ?: return
        if (!connected)   return
        ws.send(JSONObject().apply {
            put("type", "scan")
            put("data", data)
        }.toString())
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────
    private fun setStatus(msg: String, ok: Boolean) {
        tvStatus.text      = msg
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (ok) android.R.color.holo_green_light else android.R.color.darker_gray
            )
        )
    }

    private fun showCameraPanel() {
        connectPanel.visibility = View.GONE
        cameraPanel.visibility  = View.VISIBLE
    }

    private fun showConnectPanel() {
        connectPanel.visibility = View.VISIBLE
        cameraPanel.visibility  = View.GONE
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        webSocket?.close(1000, "App closed")
    }
}

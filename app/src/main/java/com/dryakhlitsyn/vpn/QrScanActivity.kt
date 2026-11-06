package com.dryakhlitsyn.vpn

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Сканирует QR-код с текстом AmneziaWG-профиля и отдаёт его в MainActivity. */
class QrScanActivity : androidx.activity.ComponentActivity() {

    private lateinit var palette: Palette
    private lateinit var previewView: PreviewView
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handled = AtomicBoolean(false)
    private var camera: androidx.camera.core.Camera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = Palettes.resolve(this)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF050506.toInt()) }

        previewView = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        root.addView(CornerFrame(this), FrameLayout.LayoutParams(-1, -1))

        val topBar = FrameLayout(this)
        val title = text("Наведи камеру на QR", 16f, 0xFFFFFFFF.toInt(), bold = true, gravity = Gravity.CENTER)
        topBar.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        val close = text("✕", 20f, 0xFFFFFFFF.toInt(), bold = true, gravity = Gravity.CENTER).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            setOnClickListener { finish() }
        }
        topBar.addView(close, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(topBar, FrameLayout.LayoutParams(-1, dp(72), Gravity.TOP).apply { topMargin = dp(24) })

        val hint = text("QR выдаёт тот, кто настраивал сервер", 12f, 0xFFB7BBC4.toInt(), gravity = Gravity.CENTER).apply {
            setPadding(dp(24), dp(12), dp(24), dp(16))
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(28) })

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        startCamera()
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: run {
                toastAndFinish("Камера недоступна — используй файл .conf")
                return@addListener
            }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { imageProxy -> analyze(imageProxy) }
            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { toastAndFinish("Не удалось включить камеру") }
        }, mainExecutor)

        previewView.postDelayed({
            if (!handled.get() && camera == null) toastAndFinish("Камера не запустилась — выбери .conf файлом")
        }, 4000)
    }

    private fun analyze(imageProxy: ImageProxy) {
        if (handled.get()) {
            imageProxy.close()
            return
        }
        val media = imageProxy.image
        if (media == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes -> onBarcodes(barcodes) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun onBarcodes(barcodes: List<Barcode>) {
        for (barcode in barcodes) {
            val value = barcode.rawValue?.trim().orEmpty()
            if (value.isEmpty()) continue
            if (!looksLikeProfile(value)) continue
            if (!handled.compareAndSet(false, true)) return
            setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, value))
            finish()
            return
        }
    }

    private fun looksLikeProfile(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("[interface]") && lower.contains("privatekey")
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private val scanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    /** Рамка-уголки для наведения. */
    private class CornerFrame(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dpF(3f)
            color = 0xFFFFFFFF.toInt()
        }
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73000000 }
        private val box = RectF()

        override fun onDraw(canvas: Canvas) {
            val side = (width * 0.68f).coerceAtMost(height * 0.5f)
            val left = (width - side) / 2f
            val top = (height - side) / 2f - context.dpF(20f)
            box.set(left, top, left + side, top + side)

            canvas.drawRect(0f, 0f, width.toFloat(), box.top, dim)
            canvas.drawRect(0f, box.bottom, width.toFloat(), height.toFloat(), dim)
            canvas.drawRect(0f, box.top, box.left, box.bottom, dim)
            canvas.drawRect(box.right, box.top, width.toFloat(), box.bottom, dim)

            val radius = context.dpF(22f)
            val arm = context.dpF(46f)
            canvas.drawRoundRect(RectF(box.left, box.top, box.left + arm, box.top + arm), radius, radius, paint)
            canvas.drawRoundRect(RectF(box.right - arm, box.top, box.right, box.top + arm), radius, radius, paint)
            canvas.drawRoundRect(RectF(box.left, box.bottom - arm, box.left + arm, box.bottom), radius, radius, paint)
            canvas.drawRoundRect(RectF(box.right - arm, box.bottom - arm, box.right, box.bottom), radius, radius, paint)
        }
    }

    companion object {
        const val EXTRA_TEXT = "qr-text"
    }
}

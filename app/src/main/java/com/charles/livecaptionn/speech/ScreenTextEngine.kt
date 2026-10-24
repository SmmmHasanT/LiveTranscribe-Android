package com.charles.livecaptionn.speech

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Reads on-screen text from the main (game) display through the same
 * MediaProjection used for system audio, with on-device ML Kit OCR.
 *
 * A text block is emitted only once it has stayed the same for two scans in a
 * row (so typewriter-style dialogue is read when complete) and differs from
 * the last emitted text.
 */
class ScreenTextEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val languageCode: String,
    private val scope: CoroutineScope,
    private val isPaused: () -> Boolean,
    private val onText: (String) -> Unit
) {
    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var job: Job? = null
    private var recognizer: TextRecognizer? = null

    private var candidate = ""
    private var lastEmitted = ""
    @Volatile private var forceNext = false

    @SuppressLint("WrongConstant")
    fun start() {
        try {
            val dm = context.getSystemService(DisplayManager::class.java)
            val main = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val metrics = DisplayMetrics().also { @Suppress("DEPRECATION") main.getRealMetrics(it) }
            // Downscale for faster OCR; text stays readable at ~1280 px wide.
            val scale = (1280f / maxOf(metrics.widthPixels, metrics.heightPixels)).coerceAtMost(1f)
            val w = (metrics.widthPixels * scale).toInt().coerceAtLeast(320)
            val h = (metrics.heightPixels * scale).toInt().coerceAtLeast(240)

            val t = HandlerThread("ScreenText").also { it.start() }
            thread = t
            val handler = Handler(t.looper)
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stop()
            }, handler)
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            display = projection.createVirtualDisplay(
                "ScreenText", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
            recognizer = if (languageCode.startsWith("ja"))
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            else
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            job = scope.launch(Dispatchers.Default) {
                delay(800)
                while (isActive) {
                    if (!isPaused()) scanOnce()
                    delay(SCAN_INTERVAL_MS)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Screen capture could not start", t)
            stop()
        }
    }

    /** Next scan emits whatever is on screen, even if unchanged. */
    fun scanNow() { forceNext = true }

    private suspend fun scanOnce() {
        val bitmap = grab() ?: return
        val text = try {
            val result = recognizer?.process(InputImage.fromBitmap(bitmap, 0))?.await() ?: return
            result.textBlocks
                .map { it.text.replace('\n', ' ').trim() }
                .filter { it.length >= MIN_BLOCK_CHARS }
                .joinToString("\n")
                .trim()
        } catch (t: Throwable) {
            Log.w(TAG, "OCR failed", t)
            return
        } finally {
            bitmap.recycle()
        }
        val force = forceNext
        if (force) {
            forceNext = false
            if (text.isNotBlank()) { lastEmitted = text; candidate = text; onText(text) }
            return
        }
        if (text.isBlank()) { candidate = ""; return }
        val stable = similar(text, candidate)
        candidate = text
        if (stable && !similar(text, lastEmitted)) {
            lastEmitted = text
            onText(text)
        }
    }

    private fun grab(): Bitmap? {
        val image = try { reader?.acquireLatestImage() } catch (_: Throwable) { null } ?: return null
        return try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            if (rowPadding == 0) padded
            else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        } catch (t: Throwable) {
            Log.w(TAG, "Frame copy failed", t)
            null
        } finally {
            image.close()
        }
    }

    fun stop() {
        job?.cancel(); job = null
        try { display?.release() } catch (_: Throwable) {}
        display = null
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
        try { recognizer?.close() } catch (_: Throwable) {}
        recognizer = null
        thread?.quitSafely(); thread = null
    }

    companion object {
        private const val TAG = "ScreenTextEngine"
        private const val SCAN_INTERVAL_MS = 1200L
        private const val MIN_BLOCK_CHARS = 2

        /** Rough equality that ignores spacing and small OCR jitter. */
        internal fun similar(a: String, b: String): Boolean {
            val x = a.filterNot { it.isWhitespace() }
            val y = b.filterNot { it.isWhitespace() }
            if (x.isEmpty() || y.isEmpty()) return x == y
            if (x == y) return true
            val longer = maxOf(x.length, y.length)
            if (kotlin.math.abs(x.length - y.length) > longer * 0.15) return false
            val bx = x.windowed(2).toSet()
            val by = y.windowed(2).toSet()
            if (bx.isEmpty() || by.isEmpty()) return false
            val inter = bx.intersect(by).size.toDouble()
            return inter / (bx.size + by.size - inter) >= 0.85
        }
    }
}

package dev.martin.friction.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dev.martin.friction.FLog
import dev.martin.friction.engine.ActiveInterruption
import dev.martin.friction.engine.InterruptionTypes
import kotlin.random.Random

/** Shows interruptions as an accessibility overlay window on top of whatever app is open. */
class InterruptionOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var shownInstance: Long? = null
    /** True while we wait for a screenshot before showing a glitch. */
    private var pending = false

    val isShowing: Boolean get() = view != null

    fun show(a: ActiveInterruption) {
        if (shownInstance == a.instance && (view != null || pending)) return
        hide()
        shownInstance = a.instance
        when (a.typeId) {
            InterruptionTypes.CLOUDS -> attach(CloudsView(service, (a.params[InterruptionTypes.DENSITY] ?: 6.0).toInt()), a)
            InterruptionTypes.GLITCH -> {
                // The glitch shreds a picture of the app, so grab one *before* our window covers it.
                pending = true
                val intensity = (a.params[InterruptionTypes.INTENSITY] ?: 6.0).toInt()
                captureScreen(retry = true) { shot ->
                    if (pending && shownInstance == a.instance) {
                        pending = false
                        attach(GlitchView(service, intensity, shot), a)
                    } else {
                        shot?.recycle()
                    }
                }
            }
            else -> FLog.w("Overlay", "no view for interruption type ${a.typeId}")
        }
    }

    private fun attach(v: View, a: ActiveInterruption) {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(v, lp)
            view = v
            FLog.d("Overlay", "showing ${a.typeId} (${a.remainingMs}ms left)")
        } catch (e: Exception) {
            FLog.e("Overlay", "addView failed", e)
        }
    }

    /** Screenshot of the current screen (Android 11+); null if unavailable. */
    private fun captureScreen(retry: Boolean, onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            onResult(null)
            return
        }
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val copy = try {
                            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            sw
                        } catch (e: Exception) {
                            FLog.e("Overlay", "screenshot conversion failed", e)
                            null
                        } finally {
                            buffer.close()
                        }
                        FLog.d("Overlay", "screenshot ${copy?.width}x${copy?.height}")
                        onResult(copy)
                    }

                    override fun onFailure(errorCode: Int) {
                        FLog.w("Overlay", "screenshot failed (code $errorCode)${if (retry) ", retrying" else ", using noise"}")
                        // 3 = ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT: Android allows ~3 screenshots/s.
                        if (retry) handler.postDelayed({ captureScreen(false, onResult) }, 400) else onResult(null)
                    }
                },
            )
        } catch (e: Exception) {
            FLog.e("Overlay", "takeScreenshot threw", e)
            onResult(null)
        }
    }

    fun hide() {
        pending = false
        shownInstance = null
        val v = view ?: return
        try {
            wm.removeView(v)
        } catch (e: Exception) {
            FLog.w("Overlay", "removeView failed", e)
        }
        view = null
        FLog.d("Overlay", "hidden")
    }
}

/** Dark, soft cloud puffs drifting across the screen. Swallows all touches. */
@SuppressLint("ViewConstructor")
class CloudsView(context: Context, density: Int) : View(context) {
    private class Puff(var x: Float, val y: Float, val r: Float, val speed: Float, val paint: Paint)

    private val d = density.coerceIn(1, 10)
    private val puffs = mutableListOf<Puff>()
    private val rnd = Random.Default
    private val startedAt = SystemClock.uptimeMillis()
    private var lastFrame = 0L
    private val baseDim = Color.argb(20 + d * 10, 0, 0, 0)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        puffs.clear()
        val count = 6 + d * 4
        val puffAlpha = (130 + d * 12).coerceAtMost(250)
        repeat(count) {
            val r = w * (0.18f + rnd.nextFloat() * 0.25f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    0f, 0f, r,
                    intArrayOf(Color.argb(255, 6, 6, 10), Color.argb(200, 10, 10, 16), Color.argb(0, 0, 0, 0)),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
                alpha = puffAlpha
            }
            val dir = if (rnd.nextBoolean()) 1f else -1f
            puffs += Puff(
                x = -w * 0.3f + rnd.nextFloat() * w * 1.6f,
                y = rnd.nextFloat() * h,
                r = r,
                speed = dir * (20f + rnd.nextFloat() * 70f),
                paint = paint,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1000f
        lastFrame = now
        val fade = ((now - startedAt) / 1500f).coerceIn(0f, 1f)

        canvas.drawColor(Color.argb((Color.alpha(baseDim) * fade).toInt(), 0, 0, 0))
        val w = width.toFloat()
        for (p in puffs) {
            p.x += p.speed * dt
            if (p.x - p.r > w) p.x = -p.r
            if (p.x + p.r < 0) p.x = w + p.r
            val saved = p.paint.alpha
            p.paint.alpha = (saved * fade).toInt()
            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.drawCircle(0f, 0f, p.r, p.paint)
            canvas.restore()
            p.paint.alpha = saved
        }
        postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean = true
}

/**
 * Glitch: the screen is cut into horizontal bands that jump sideways and change colour,
 * re-rolled several times a second. Higher intensity = thinner bands, more of them glitched,
 * bigger shifts. At 10 every band is glitched and thinner than a line of text: unreadable.
 * Without a screenshot (Android < 11 or a failure) the glitched bands are solid noise.
 */
@SuppressLint("ViewConstructor")
class GlitchView(context: Context, intensity: Int, private var shot: Bitmap?) : View(context) {
    private class Band(val top: Int, val height: Int, val dx: Int, val paint: Paint?, val noise: Int)

    private val f = intensity.coerceIn(1, 10) / 10f
    private val rnd = Random.Default
    private val bands = ArrayList<Band>()
    private val plain = Paint(Paint.FILTER_BITMAP_FLAG)
    private val noisePaint = Paint()
    private val src = Rect()
    private val dst = Rect()
    private val dp = resources.displayMetrics.density

    private val filters: List<Paint> = listOf(
        // channel rotation R<-G, G<-B, B<-R
        ColorMatrix(floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
        // channel rotation R<-B, G<-R, B<-G
        ColorMatrix(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
        // invert
        ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)),
        // red only, boosted
        ColorMatrix(floatArrayOf(1.6f, 0f, 0f, 0f, 30f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
        // cyan (no red)
        ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1.3f, 0f, 0f, 20f, 0f, 0f, 1.3f, 0f, 20f, 0f, 0f, 0f, 1f, 0f)),
    ).map { m -> Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) } }

    private val noiseColors = intArrayOf(
        Color.rgb(255, 0, 90), Color.rgb(0, 255, 200), Color.rgb(30, 30, 255),
        Color.rgb(255, 230, 0), Color.rgb(20, 20, 20), Color.rgb(240, 240, 240),
    )

    private fun regenerate() {
        bands.clear()
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val maxH = (48f + (3f - 48f) * f) * dp          // 48dp bands at 1 .. 3dp at 10
        val minH = maxOf(2f, maxH / 2.5f)
        val p = 0.08f + 0.92f * f                        // share of bands that glitch
        val maxShift = w * (0.03f + 0.35f * f)
        val colorChance = 0.3f + 0.6f * f
        var y = 0
        while (y < h) {
            val bh = (minH + rnd.nextFloat() * (maxH - minH)).toInt().coerceAtLeast(2)
            if (rnd.nextFloat() < p) {
                var dx = ((rnd.nextFloat() * 2f - 1f) * maxShift).toInt()
                if (f >= 0.95f && kotlin.math.abs(dx) < w * 0.1f) dx = if (dx < 0) -(w * 0.1f).toInt() else (w * 0.1f).toInt()
                val paint = if (rnd.nextFloat() < colorChance) filters[rnd.nextInt(filters.size)] else null
                bands += Band(y, bh, dx, paint, noiseColors[rnd.nextInt(noiseColors.size)])
            }
            y += bh
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        regenerate()
        val w = width
        val h = height
        val bmp = shot
        if (bmp != null && !bmp.isRecycled) {
            val sy = bmp.height / h.toFloat()
            // Below full intensity the untouched parts show the app as it was; at 10 nothing is left in place.
            if (f < 0.95f) {
                dst.set(0, 0, w, h)
                canvas.drawBitmap(bmp, null, dst, plain)
            } else {
                canvas.drawColor(Color.BLACK)
            }
            for (b in bands) {
                src.set(0, (b.top * sy).toInt(), bmp.width, ((b.top + b.height) * sy).toInt())
                val paint = b.paint ?: plain
                dst.set(b.dx, b.top, b.dx + w, b.top + b.height)
                canvas.drawBitmap(bmp, src, dst, paint)
                // wrap around so the band has no gap
                val wrap = if (b.dx > 0) b.dx - w else b.dx + w
                dst.set(wrap, b.top, wrap + w, b.top + b.height)
                canvas.drawBitmap(bmp, src, dst, paint)
            }
        } else {
            for (b in bands) {
                noisePaint.color = b.noise
                noisePaint.alpha = (160 + 95 * f).toInt()
                val x0 = (b.dx.coerceAtLeast(0)).toFloat()
                canvas.drawRect(x0, b.top.toFloat(), w.toFloat(), (b.top + b.height).toFloat(), noisePaint)
            }
        }
        // Re-roll 7-16 times a second, faster when more intense.
        postInvalidateDelayed((60 + rnd.nextInt((90 - 40 * f).toInt().coerceAtLeast(10))).toLong())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        shot?.recycle()
        shot = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean = true
}

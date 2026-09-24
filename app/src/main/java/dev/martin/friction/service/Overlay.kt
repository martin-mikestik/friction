package dev.martin.friction.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
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
    private var view: View? = null
    private var shownInstance: Long? = null

    val isShowing: Boolean get() = view != null

    fun show(a: ActiveInterruption) {
        if (view != null && shownInstance == a.instance) return
        hide()
        val v: View = when (a.typeId) {
            InterruptionTypes.CLOUDS -> CloudsView(service, (a.params[InterruptionTypes.DENSITY] ?: 6.0).toInt())
            else -> {
                FLog.w("Overlay", "no view for interruption type ${a.typeId}")
                return
            }
        }
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
            shownInstance = a.instance
            FLog.d("Overlay", "showing ${a.typeId} (${a.remainingMs}ms left)")
        } catch (e: Exception) {
            FLog.e("Overlay", "addView failed", e)
        }
    }

    fun hide() {
        val v = view ?: return
        try {
            wm.removeView(v)
        } catch (e: Exception) {
            FLog.w("Overlay", "removeView failed", e)
        }
        view = null
        shownInstance = null
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

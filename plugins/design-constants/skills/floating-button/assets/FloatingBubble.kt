package com.example.app // TODO: your package

// The floating button over other apps. Reference: DrumTrig (FloatingBubble.kt). Change the
// TODOs: the app name, its accent colour, and what "close" means for this app.

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import android.view.animation.DecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Where a released bubble settles: against the nearer side edge, inside the safe top and bottom margins. */
internal data class BubbleSpot(val x: Int, val y: Int, val onRight: Boolean)

/**
 * [centerX]: the bubble's centre when let go. [size]: the bubble window's width and height.
 * [left] and [right] are the edges of the safe area (inside the navigation buttons and any
 * camera cutout). The bubble moves to the nearer one and its top is clamped to [minY]..[maxY].
 */
internal fun bubbleSnap(centerX: Float, y: Int, size: Int, left: Int, right: Int, minY: Int, maxY: Int): BubbleSpot {
    val onRight = centerX > (left + right) / 2f
    return BubbleSpot(if (onRight) right - size else left, y.coerceIn(minY, maxOf(minY, maxY)), onRight)
}

/** True when the bubble's centre is close enough to the ✕ target's centre to close the app on release. */
internal fun overHideTarget(bx: Float, by: Float, tx: Float, ty: Float, radius: Float): Boolean =
    hypot(bx - tx, by - ty) <= radius

/**
 * The floating button shown over other apps while the app keeps working in the background.
 * Tap it to come back to the app; drag it onto the ✕ at the bottom to close the app completely
 * (work stopped, service and notification gone). [flash] gives it a short glow, for the app's
 * own "something happened" (DrumTrig: every drum hit). Needs Android's "Display over other
 * apps" permission. All calls are safe from any thread.
 */
// Holds only the application context and the overlay views, which are removed in hide().
@SuppressLint("StaticFieldLeak")
object FloatingBubble {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var wm: WindowManager? = null
    private var bubble: BubbleView? = null
    private var target: TargetView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var callbacks: ComponentCallbacks? = null

    // Where the bubble sits, kept while the app runs so it comes back to the same place.
    private var onRight = true
    private var yFrac = 0.3f

    /** TODO: the app's name, as people see it ("Open DrumTrig", "Close DrumTrig"). */
    const val APP = "MyApp"
    /** TODO: the app's accent colour (DrumTrig's teal), for the disc and its glow. */
    private const val ACCENT = 0xFF16B98A.toInt()
    /** The colour of the mark on the disc. */
    private const val ON_ACCENT = 0xFF03160F.toInt()

    fun allowed(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /**
     * Closes the app the way its own "exit" does (set by the main activity while it exists).
     * Without it (the screen was already destroyed) the bubble stops [KeepAliveService] itself,
     * after [onCloseWithoutScreen].
     */
    @Volatile var onClose: (() -> Unit)? = null

    /** TODO: stop the app's background work when there is no screen to ask (DrumTrig: Native.stop()). */
    @Volatile var onCloseWithoutScreen: (() -> Unit)? = null

    /**
     * Optional: a "Lock" that must not be undone by a stray drag (DrumTrig's Lock). While it
     * says true, the ✕ only hides the button. Leave null if the app has no such mode.
     */
    @Volatile var isLocked: (() -> Boolean)? = null

    private fun lockedNow(): Boolean = isLocked?.invoke() ?: false

    /**
     * Closes the app from outside its screen: the notification's Stop button. Same rules as the
     * ✕: refused while Locked.
     */
    fun requestClose(ctx: Context) {
        if (app == null) app = ctx.applicationContext
        main.post { closeApp() }
    }

    /**
     * Dropped on the ✕ (or TalkBack's "Close <App>"). While Locked, like the app's own exit, it
     * does not close the app: the button is only hidden and the work keeps running.
     */
    private fun closeApp() {
        hideNow()
        if (lockedNow()) {
            app?.let { Toast.makeText(it, "Locked. $APP keeps running. Turn off Lock to close it.", Toast.LENGTH_LONG).show() }
            return
        }
        val close = onClose
        if (close != null) {
            close()
        } else {
            app?.let { c ->
                onCloseWithoutScreen?.invoke()
                c.stopService(Intent(c, KeepAliveService::class.java))
            }
        }
    }

    fun show(ctx: Context) {
        val c = ctx.applicationContext
        main.post { if (bubble == null && allowed(c)) showNow(c) }
    }

    fun hide() {
        main.post { hideNow() }
    }

    /** Something happened (DrumTrig: a hit): a short glow around the bubble. */
    fun flash() {
        main.post { bubble?.flash() }
    }

    private fun dp(v: Float): Int = (v * (app?.resources?.displayMetrics?.density ?: 1f)).roundToInt()

    private fun screen(): Pair<Int, Int> {
        val w = wm!!
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = w.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") w.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    /**
     * The part of the screen clear of the status and navigation bars and any camera cutout
     * (counted even while the bars are hidden), so the bubble and the ✕ never sit under them.
     */
    private fun safe(): Rect {
        val (sw, sh) = screen()
        if (Build.VERSION.SDK_INT < 30) return Rect(0, 0, sw, sh)
        val i = wm!!.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Rect(i.left, i.top, sw - i.right, sh - i.bottom)
    }

    private fun minY() = safe().top + dp(16f)
    private fun maxY(size: Int) = safe().bottom - size - dp(16f)

    private fun snapTo(centerX: Float, y: Int, size: Int): BubbleSpot {
        val s = safe()
        return bubbleSnap(centerX, y, size, s.left, s.right, minY(), maxY(size))
    }

    private fun overlayParams(touchable: Boolean, size: Int, height: Int = size) = WindowManager.LayoutParams(
        size, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    private fun showNow(c: Context) {
        app = c
        wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = dp(BubbleView.SIZE_DP)
        val (sw, sh) = screen()
        val spot = snapTo(if (onRight) sw.toFloat() else 0f, (yFrac * sh).roundToInt(), size)
        val p = overlayParams(true, size).apply { x = spot.x; y = spot.y }
        // The ✕ target goes in first, hidden, so the bubble is always drawn above it.
        val (tcx, tcy) = targetCenter()
        val tp = overlayParams(false, dp(TargetView.W_DP), dp(TargetView.H_DP)).apply {
            x = (tcx - dp(TargetView.W_DP) / 2f).roundToInt(); y = (tcy - dp(TargetView.CIRCLE_Y_DP)).roundToInt()
        }
        val tv = TargetView(c).apply { visibility = View.INVISIBLE }
        val v = BubbleView(c)
        try {
            wm!!.addView(tv, tp)
            target = tv
            wm!!.addView(v, p)
        } catch (e: Exception) {
            hideNow() // permission withdrawn in the meantime
            return
        }
        bubble = v
        lp = p
        val cb = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                main.post { resnap() }
            }
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {}
        }
        c.registerComponentCallbacks(cb)
        callbacks = cb
    }

    private fun hideNow() {
        val w = wm ?: return
        bubble?.stopAnimations()
        bubble?.let { runCatching { w.removeViewImmediate(it) } }
        target?.let { runCatching { w.removeViewImmediate(it) } }
        callbacks?.let { app?.unregisterComponentCallbacks(it) }
        bubble = null
        target = null
        lp = null
        callbacks = null
    }

    /** After a rotation: the same side and the same height, as a share of the screen. */
    private fun resnap() {
        val v = bubble ?: return
        val p = lp ?: return
        val (sw, sh) = screen()
        val s = snapTo(if (onRight) sw.toFloat() else 0f, (yFrac * sh).roundToInt(), p.width)
        p.x = s.x
        p.y = s.y
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private fun move(x: Int, y: Int) {
        val v = bubble ?: return
        val p = lp ?: return
        p.x = x
        p.y = y
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private fun targetCenter(): Pair<Float, Float> {
        // The ✕'s centre, high enough that its label fits below it inside the safe area.
        val s = safe()
        return s.exactCenterX() to (s.bottom - dp(16f) - dp(TargetView.H_DP - TargetView.CIRCLE_Y_DP)).toFloat()
    }

    private fun showTarget() {
        val v = target ?: return
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        // Placed again each time, in case the screen turned since the last drag.
        val (cx, cy) = targetCenter()
        p.x = (cx - p.width / 2f).roundToInt()
        p.y = (cy - dp(TargetView.CIRCLE_Y_DP)).roundToInt()
        v.setActive(false, lockedNow())
        v.visibility = View.VISIBLE
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private fun hideTarget() {
        target?.visibility = View.INVISIBLE
    }

    private fun openApp() {
        val c = app ?: return
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { c.startActivity(i) }
        hideNow()
    }

    // ------------------------------------------------------------ the bubble

    @SuppressLint("ViewConstructor")
    private class BubbleView(c: Context) : View(c) {
        companion object {
            /** The window, glow ring included. The disc itself is 52 dp across. */
            const val SIZE_DP = 68f
        }

        private val d = c.resources.displayMetrics.density
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ON_ACCENT; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.STROKE }
        private val path = Path()
        private var glowLevel = 0f
        private var glowAnim: ValueAnimator? = null
        private var snapAnim: ValueAnimator? = null

        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var overTarget = false

        init {
            contentDescription = "$APP is running. Open $APP" // TODO: say what it is doing (DrumTrig: "listening")
            isClickable = true
            setOnClickListener { openApp() }
            ViewCompat.addAccessibilityAction(this, "Close $APP") { _, _ -> closeApp(); true }
            ViewCompat.replaceAccessibilityAction(this, AccessibilityActionCompat.ACTION_CLICK, "Open $APP", null)
        }

        fun stopAnimations() {
            glowAnim?.cancel()
            snapAnim?.cancel()
        }

        fun flash() {
            glowAnim?.cancel()
            glowLevel = 1f
            invalidate()
            if (ValueAnimator.areAnimatorsEnabled()) {
                glowAnim = ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 260
                    startDelay = 80
                    addUpdateListener { glowLevel = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else {
                // Animations off: a plain on-off blink, still long enough to see.
                postDelayed({ glowLevel = 0f; invalidate() }, 160)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = 26f * d
            if (glowLevel > 0f) {
                glow.strokeWidth = 4f * d
                glow.alpha = (glowLevel * 230).roundToInt()
                canvas.drawCircle(cx, cy, r + 4f * d, glow)
            }
            canvas.drawCircle(cx, cy + 1.5f * d, r + 1f * d, shade)
            canvas.drawCircle(cx, cy, r, disc)
            // TODO: the app's own mark, drawn in [mark]. DrumTrig's: a ring with a pulse through it.
            val k = r / 22f
            mark.strokeWidth = 2.5f * k
            canvas.drawCircle(cx, cy, 13f * k, mark)
            path.reset()
            path.moveTo(cx - 13f * k, cy)
            path.lineTo(cx - 6f * k, cy)
            path.lineTo(cx - 3f * k, cy - 9f * k)
            path.lineTo(cx + 3f * k, cy + 9f * k)
            path.lineTo(cx + 6f * k, cy)
            path.lineTo(cx + 13f * k, cy)
            canvas.drawPath(path, mark)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = lp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnim?.cancel()
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    dragging = false
                    overTarget = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        showTarget()
                    }
                    if (dragging) {
                        val (tx, ty) = targetCenter()
                        val near = overHideTarget(e.rawX, e.rawY, tx, ty, 56f * d)
                        if (near != overTarget) {
                            overTarget = near
                            target?.setActive(near, lockedNow())
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                        if (near) {
                            // Pulled onto the ✕, so letting go there is easy to aim.
                            move((tx - width / 2f).roundToInt(), (ty - height / 2f).roundToInt())
                        } else {
                            move(startX + dx.roundToInt(), startY + dy.roundToInt())
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        performClick()
                    } else if (overTarget) {
                        closeApp()
                    } else {
                        hideTarget()
                        settle()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideTarget()
                    if (dragging) settle()
                }
            }
            return true
        }

        private fun settle() {
            val p = lp ?: return
            val sh = screen().second
            val s = snapTo(p.x + width / 2f, p.y, width)
            onRight = s.onRight
            yFrac = s.y.toFloat() / sh
            val fx = p.x
            val fy = p.y
            snapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    move((fx + (s.x - fx) * t).roundToInt(), (fy + (s.y - fy) * t).roundToInt())
                }
                start()
            }
        }
    }

    // ------------------------------------------------------------ the ✕ target

    @SuppressLint("ViewConstructor")
    private class TargetView(c: Context) : View(c) {
        companion object {
            /** The window: the ✕ on top, room for its label below. Not touchable. */
            const val W_DP = 220f
            const val H_DP = 104f
            const val CIRCLE_Y_DP = 36f
        }

        private val d = c.resources.displayMetrics.density
        // Dark colours whatever the theme: the ✕ sits over other apps, so it carries its own
        // backing. (DrumTrig takes these from its dark palette.)
        private val fg = 0xFFF3F6F9.toInt()
        private val idleFill = 0xCC20252C.toInt()   // a dark menu colour at 80%
        private val danger = 0xFFE5484D.toInt()
        private val onDanger = 0xFFFFFFFF.toInt()
        private val warn = 0xFFF5A524.toInt()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = idleFill }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1f * d
        }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND
        }
        private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEB20252C.toInt() }
        private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 13f, c.resources.displayMetrics)
            textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        private var active = false
        private var label = ""

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        /**
         * [on]: the bubble is over the ✕. Red with "Close <App>", or, while Locked, amber with
         * "Locked: hides the button", so it is clear before letting go what a drop will do.
         */
        fun setActive(on: Boolean, locked: Boolean) {
            active = on
            fill.color = when { !on -> idleFill; locked -> warn; else -> danger }
            cross.color = if (on) onDanger else fg
            label = when { !on -> ""; locked -> "Locked: hides the button"; else -> "Close $APP" }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = CIRCLE_Y_DP * d
            val r = (if (active) 34f else 26f) * d
            canvas.drawCircle(cx, cy, r, fill)
            if (!active) canvas.drawCircle(cx, cy, r, edge)
            val a = 7f * d
            canvas.drawLine(cx - a, cy - a, cx + a, cy + a, cross)
            canvas.drawLine(cx - a, cy + a, cx + a, cy - a, cross)
            if (label.isNotEmpty()) {
                // A pill behind the text, so it reads over any app.
                val tw = labelText.measureText(label)
                val top = cy + 40f * d
                val h = 24f * d
                canvas.drawRoundRect(cx - tw / 2f - 10f * d, top, cx + tw / 2f + 10f * d, top + h, h / 2f, h / 2f, labelBg)
                val fm = labelText.fontMetrics
                canvas.drawText(label, cx, top + h / 2f - (fm.ascent + fm.descent) / 2f, labelText)
            }
        }
    }
}

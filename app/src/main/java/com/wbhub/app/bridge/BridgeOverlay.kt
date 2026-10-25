package com.wbhub.app.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A small always-visible panel showing the bridge's state.
 *
 * It is a real, readable window rather than a hidden one-pixel view: a window
 * the user can see is what keeps the process at visible importance, which is
 * what stops the system freezing it while it serves requests.
 *
 * Dragging is handled with raw touch deltas. A move threshold separates a drag
 * from a tap, so the panel can be repositioned without accidentally toggling
 * its detail view.
 */
class BridgeOverlay(
    private val context: Context,
    private val onClose: () -> Unit,
    panelOpacity: Float = 0.94f,
    /** When locked the panel cannot be dragged, so it stays where it was put. */
    locked: Boolean = false,
    /** Whether the panel is replaced by a non-interactive dot. */
    private var stealth: Boolean = false,
) {

    /**
     * Panel opacity; changing it repaints the panel immediately.
     *
     * Ignored in stealth mode: the dot derives its alpha from its own colour so
     * one setting does not fight the other.
     */
    var panelOpacity: Float = panelOpacity
        set(value) {
            field = value
            if (!stealth) applyBackground()
        }

    /** Whether dragging is disabled; toggled live from the settings screen. */
    var locked: Boolean = locked

    /**
     * Whether the overlay is a dot.
     *
     * Switching rebuilds the window rather than hiding views: the touchability
     * flag is a property of the window, so it can only be changed by removing
     * and re-adding it.
     */
    var stealthMode: Boolean = stealth
        set(value) {
            if (field == value) return
            field = value
            if (view != null) {
                hide()
                show()
            }
        }

    /**
     * Whether the dot is temporarily accepting touches so it can be moved.
     *
     * A passthrough dot cannot be dragged, which would leave its position
     * unchangeable, so adjustment is a mode the user enters and leaves rather
     * than something always available.
     */
    var adjusting: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (stealthMode && view != null) {
                hide()
                show()
            }
        }

    /** Dot appearance, read at build time so a change needs a rebuild. */
    var dotColor: Int = BridgeSettings.DEFAULT_DOT_COLOR
    var dotSize: Int = BridgeSettings.DEFAULT_DOT_SIZE
    var dotShape: DotShape = DotShape.FILLED
    /**
     * Dot opacity. Zero is meaningful: the window stays and keeps the process
     * visible while showing nothing at all.
     */
    var dotAlpha: Float = BridgeSettings.DEFAULT_DOT_ALPHA

    /** Whether the dot cycles through neighbouring positions. */
    var burnInEnabled: Boolean = true

    /** How long it stays in one position. */
    var burnInIntervalMs: Long = BridgeSettings.DEFAULT_BURN_IN_INTERVAL_MS

    /**
     * Where the dot sits when it is not displaced: the user's own position.
     *
     * Every step is measured from here rather than from the last position, so a
     * round of moves cannot accumulate into a drift away from where it was put.
     */
    private var anchorX = 0
    private var anchorY = 0

    /** Index into [OFFSETS] of the position currently held. */
    private var offsetIndex = 0

    private val burnInTicker = object : Runnable {
        override fun run() {
            stepBurnIn()
            handler.postDelayed(this, burnInIntervalMs)
        }
    }

    /**
     * Applies a change to the dot's appearance.
     *
     * Only a size or shape change rebuilds the window: those change the view's
     * geometry. Colour and opacity are repainted in place, because rebuilding
     * happens by removing and re-adding the window, and doing that on every
     * frame of a slider drag reads as a flicker rather than a fade.
     */
    fun refreshAppearance(color: Int, size: Int, shape: DotShape, alpha: Float) {
        val needsRebuild = size != dotSize || shape != dotShape
        val needsRepaint = color != dotColor || alpha != dotAlpha
        dotColor = color
        dotSize = size
        dotShape = shape
        dotAlpha = alpha
        if (!stealthMode || view == null) return
        when {
            needsRebuild -> {
                hide()
                show()
            }
            needsRepaint -> paintDot()
        }
        // An invisible dot burns nothing in, so the cycle is pointless; it also
        // stops and restarts when the opacity changes either way.
        scheduleBurnIn()
    }

    /** Applies a change to the burn-in settings. */
    fun refreshBurnIn(enabled: Boolean, intervalMs: Long) {
        burnInEnabled = enabled
        burnInIntervalMs = intervalMs
        scheduleBurnIn()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var titleView: TextView
    private lateinit var detailView: TextView
    private lateinit var logView: TextView
    private lateinit var root: LinearLayout
    /** 面板/圆点的实际内容视图：背景与透明度画在它身上，不是外层容器。 */
    private var contentView: View? = null

    /**
     * The dot's view and its drawable, kept so a blink can repaint the shape
     * instead of fading the whole view.
     *
     * The colour already carries the user's opacity, so fading the view as well
     * would multiply the two and leave the dot darker after every blink.
     */
    private var dotView: View? = null
    private var dotDrawable: GradientDrawable? = null

    /** Whether the panel shows its request line; tapping toggles it. */
    private var expanded = true

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    /** Last request count seen, so a change can be turned into a blink. */
    private var lastSeenCount = 0

    /**
     * The running blink, so a new one can replace it instead of stacking.
     *
     * Held rather than left to the animator's own lifetime: two animators on
     * the same property fight, and whichever finishes last decides the alpha.
     */
    private var blink: android.animation.ValueAnimator? = null

    /**
     * Flashes the dot when a call has been served.
     *
     * The dot reports nothing on its own, so a blink is the only signal it can
     * give that the bridge is doing something; without it a working endpoint and
     * a stalled one look identical.
     */
    private fun blinkIfCalled() {
        val count = BridgeStatus.requestCount.get()
        // A restart zeroes the counter. Reading that as a change would blink on
        // a counter that was reset rather than on a call that arrived, so the
        // baseline follows the counter down instead.
        if (count < lastSeenCount) {
            lastSeenCount = count
            return
        }
        if (count == lastSeenCount) return
        lastSeenCount = count
        if (dotView == null) return
        blink?.cancel()
        // The blink animates the dot's actual opacity rather than a multiplier,
        // because at zero there is nothing to multiply: a dot the user set to
        // invisible has to rise towards visible and settle back to nothing.
        // Its peak is transient and never becomes the configured value.
        val peak = if (dotAlpha <= 0.01f) BLINK_PEAK_INVISIBLE else dotAlpha
        val trough = if (dotAlpha <= 0.01f) 0f else dotAlpha * BLINK_DIM
        blink = android.animation.ValueAnimator.ofFloat(peak, trough, peak).apply {
            duration = BLINK_MS
            addUpdateListener { animator ->
                paintAlpha(animator.animatedValue as Float)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Restoring explicitly covers cancellation, which skips the
                    // final frame and would otherwise leave the dot dimmed.
                    paintAlpha(dotAlpha)
                }
            })
            start()
        }
    }

    /** Redraws the dot at the configured opacity. */
    private fun paintDot() = paintAlpha(dotAlpha)

    /** Redraws the dot at an absolute [alpha]. */
    private fun paintAlpha(alpha: Float) {
        val drawable = dotDrawable ?: return
        val color = withAlpha(dotColor, alpha.coerceIn(0f, 1f))
        when (dotShape) {
            DotShape.FILLED -> drawable.setColor(color)
            DotShape.RING -> {
                drawable.setColor(Color.TRANSPARENT)
                drawable.setStroke(dp(if (dotSize >= 20) 3 else 2), color)
            }
        }
        dotView?.invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val content: View = if (stealthMode) buildDot() else buildPanel()
        contentView = content
        root = LinearLayout(context).apply { addView(content) }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Not focusable, so the panel never takes input away from the app
            // behind it. The dot goes further and declares itself untouchable,
            // which is what lets a tap land on whatever is underneath — except
            // while it is being moved, when it has to accept the drag.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                if (stealthMode && !adjusting) {
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    0
                },
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // A previously dragged position wins, so the window reappears where
            // the user left it. The dot keeps its own coordinates: the two are
            // different sizes and want different corners.
            val (savedX, savedY) = if (stealthMode) {
                BridgeSettings.dotPosition(context).takeIf { it.first >= 0 || it.second >= 0 }
                    ?: BridgeSettings.position(context)
            } else {
                BridgeSettings.position(context)
            }
            x = if (savedX >= 0) savedX else dp(8)
            y = if (savedY >= 0) savedY else dp(64)
        }
        params = lp

        if (!stealthMode) applyBackground()
        runCatching { wm.addView(root, lp) }
            .onFailure { BridgeStatus.recordError("悬浮窗添加失败：${it.message?.take(50)}") }

        // Nothing to drag on a passthrough dot; a panel always drags, and the
        // dot drags only while it is being adjusted.
        if (!stealthMode || adjusting) attachDrag()
        view = root
        // The dot starts at the user's position and walks from there; the round
        // is reset on every rebuild so a re-added window does not resume at an
        // arbitrary corner of the ring.
        anchorX = lp.x
        anchorY = lp.y
        offsetIndex = 0
        if (!stealthMode) refresh()
        // A dot is polled faster: its only job is to notice a call, and a
        // one-second poll would delay the blink by up to that much.
        handler.postDelayed(ticker, if (stealthMode) DOT_REFRESH_MS else REFRESH_MS)
        scheduleBurnIn()
    }

    /** The full status panel. */
    private fun buildPanel(): View {
        titleView = TextView(context).apply {
            setTextColor(Color.parseColor("#7FE3A0"))
            textSize = 12f
        }
        detailView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
        }
        logView = TextView(context).apply {
            setTextColor(Color.parseColor("#B9C4D4"))
            textSize = 10f
            maxLines = 2
        }
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FF8A80"))
            textSize = 12f
            setPadding(dp(10), dp(2), 0, 0)
            setOnClickListener { onClose() }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close)

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#3A4A63"))
            }
            addView(header)
            addView(detailView)
            addView(logView)
        }
    }

    /**
     * The stealth indicator: a plain shape with no text and no handler.
     *
     * Its whole purpose is to keep the process visible, so it deliberately
     * carries no interaction at all.
     */
    private fun buildDot(): View {
        val side = dp(dotSize)
        val stroke = dp(if (dotSize >= 20) 3 else 2)
        val color = withAlpha(dotColor, dotAlpha)
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when (dotShape) {
                DotShape.FILLED -> setColor(color)
                DotShape.RING -> {
                    setColor(Color.TRANSPARENT)
                    setStroke(stroke, color)
                }
            }
        }
        dotDrawable = drawable
        return View(context).apply {
            background = drawable
            layoutParams = LinearLayout.LayoutParams(side, side)
        }.also { dotView = it }
    }

    /** Drag to move, tap to toggle the detail line. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        root.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            // The lock is read per gesture rather than at bind time, so
            // toggling it in the settings screen takes effect immediately.
            // A locked panel still consumes touches: letting them through would
            // make the panel a hole the app behind it could be tapped through.
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // A locked panel stays put; a dot being adjusted ignores the
                    // lock, since the user explicitly asked to move it.
                    val canDrag = !locked || (stealthMode && adjusting)
                    if (canDrag) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                        if (moved) {
                            lp.x = startX + dx.roundToInt()
                            lp.y = startY + dy.roundToInt()
                            clampToScreen(lp)
                            runCatching { windowManager?.updateViewLayout(root, lp) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // A tap collapses the panel to its title so it can be tucked
                    // out of the way; a dot has nothing to collapse. Only an
                    // actual drag records the position.
                    if (!moved) {
                        if (!stealthMode) toggleExpanded()
                    } else if (!locked || (stealthMode && adjusting)) {
                        clampToScreen(lp)
                        if (stealthMode) {
                            BridgeSettings.setDotPosition(context, lp.x, lp.y)
                            // The user just chose a new home position, so the
                            // ring recentres on it rather than on the old one.
                            anchorX = lp.x
                            anchorY = lp.y
                            offsetIndex = 0
                        } else {
                            BridgeSettings.setPosition(context, lp.x, lp.y)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleExpanded() {
        expanded = !expanded
        detailView.visibility = if (expanded) View.VISIBLE else View.GONE
        logView.visibility = if (expanded) View.VISIBLE else View.GONE
        refresh()
    }

    /** The running move animation, so a new step can replace it. */
    private var moveAnimator: android.animation.ValueAnimator? = null

    /**
     * Moves the dot one step around the anchor.
     *
     * The sequence is a fixed walk around the ring rather than a random pick:
     * a random one can land on the same side twice in a row, which would leave
     * that cluster of pixels lit for two intervals while others stay dark.
     */
    private fun stepBurnIn() {
        if (!stealthMode || adjusting) return
        if (!burnInEnabled) return
        val lp = params ?: return
        val root = view ?: return

        val step = dp(dotSize)
        // Eight directions around a ring whose radius is wider than the dot.
        //
        // Eight evenly spaced points on a circle of radius D would put two
        // neighbouring ones only 0.77 D apart, since the chord between them is
        // 2·D·sin(22.5°), which is closer than the dot is wide. The radius is
        // therefore 1.32 D: the smallest value that keeps every pair of
        // positions — adjacent or diagonal — at least a full diameter apart.
        val radius = (step * RING_RADIUS_FACTOR).roundToInt()
        val diagonal = (radius / SQRT_2).roundToInt()
        offsetIndex = (offsetIndex + 1) % OFFSETS.size
        val candidate = OFFSETS[offsetIndex]
        val x = anchorX + if (candidate.first == 0) 0 else candidate.first * (if (candidate.second == 0) radius else diagonal)
        val y = anchorY + if (candidate.second == 0) 0 else candidate.second * (if (candidate.first == 0) radius else diagonal)

        // Moving past an edge would push part of the dot off screen, so that
        // step is skipped: the position counter has already advanced, which
        // means the next interval tries the following direction instead of
        // hammering the same blocked one.
        if (!fitsOnScreen(x, y)) return

        if (burnInIntervalMs <= FADE_THRESHOLD_MS) {
            // At a one-second interval a fade would take most of the time and
            // the dot would appear to strobe rather than to sit somewhere.
            lp.x = x
            lp.y = y
            runCatching { windowManager?.updateViewLayout(root, lp) }
        } else {
            moveSmoothly(root, lp, x, y)
        }
    }

    /** Fades out, repositions, fades back in. */
    private fun moveSmoothly(root: View, lp: WindowManager.LayoutParams, x: Int, y: Int) {
        moveAnimator?.cancel()
        moveAnimator = android.animation.ValueAnimator.ofFloat(1f, 0f, 1f).apply {
            duration = FADE_MS * 2
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                root.alpha = value
                // Reposition at the darkest point, so the jump is not seen.
                if (value <= FADE_SWAP_AT && (lp.x != x || lp.y != y)) {
                    lp.x = x
                    lp.y = y
                    runCatching { windowManager?.updateViewLayout(root, lp) }
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    root.alpha = 1f
                }
            })
            start()
        }
    }

    /** Whether a position keeps the whole dot inside the screen. */
    private fun fitsOnScreen(x: Int, y: Int): Boolean {
        val metrics = context.resources.displayMetrics
        val side = dp(dotSize)
        return x >= 0 && y >= 0 &&
            x + side <= metrics.widthPixels && y + side <= metrics.heightPixels
    }

    /** Starts the cycle, if the current mode and settings call for it. */
    private fun scheduleBurnIn() {
        handler.removeCallbacks(burnInTicker)
        if (!stealthMode || !burnInEnabled || dotAlpha <= 0.01f) return
        handler.postDelayed(burnInTicker, burnInIntervalMs)
    }

    fun hide() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(burnInTicker)
        moveAnimator?.cancel()
        moveAnimator = null
        // A blink that outlives its view would keep animating a detached view
        // and, worse, its end-listener would write to it after the window is
        // gone.
        blink?.cancel()
        blink = null
        val v = view ?: return
        runCatching { windowManager?.removeView(v) }
        view = null
        contentView = null
    }

    fun isShowing(): Boolean = view != null

    private fun refresh() {
        // The dot has nothing to report, and its text views were never built.
        if (stealthMode) {
            blinkIfCalled()
            return
        }
        val running = BridgeStatus.running
        titleView.text = if (running) "● 转发中" else "○ 已停止"
        titleView.setTextColor(
            if (running) Color.parseColor("#7FE3A0") else Color.parseColor("#FF8A80"),
        )
        if (!expanded) return

        detailView.text = if (running) {
            "端口 ${BridgeStatus.port} · 已服务 ${BridgeStatus.requestCount.get()} 次"
        } else {
            "服务未运行"
        }
        logView.text = when {
            BridgeStatus.lastError.isNotEmpty() -> "⚠ ${BridgeStatus.lastError}"
            BridgeStatus.lastRequest.isNotEmpty() -> "↳ ${BridgeStatus.lastRequest}"
            else -> "等待请求…"
        }
        logView.setTextColor(
            if (BridgeStatus.lastError.isNotEmpty()) {
                Color.parseColor("#FFB4A9")
            } else {
                Color.parseColor("#B9C4D4")
            },
        )
    }

    /** Paints the panel background at the current opacity. */
    private fun applyBackground() {
        // 背景设在 content（buildPanel），外层 root 没有 drawable；读 root 会让
        // 透明度滑块永远无效。
        val content = contentView ?: return
        val drawable = (content.background as? GradientDrawable) ?: return
        drawable.setColor(withAlpha(Color.parseColor("#1A2233"), panelOpacity))
        content.invalidate()
    }

    /** Applies the configured opacity to a base colour. */
    private fun withAlpha(color: Int, alpha: Float): Int {
        // Zero is a valid choice here, unlike the panel where an invisible panel
        // would defeat its own purpose.
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    /** 拖动时把窗口限制在屏幕内，避免坐标持久化到屏幕外再也找不回来。 */
    private fun clampToScreen(lp: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val width = root.width.takeIf { it > 0 } ?: root.measuredWidth
        val height = root.height.takeIf { it > 0 } ?: root.measuredHeight
        if (width > 0) lp.x = lp.x.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        if (height > 0) lp.y = lp.y.coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_MS = 1000L
        const val DOT_REFRESH_MS = 250L
        const val BLINK_MS = 450L

        /** How far the blink dips; a full fade reads as a disappearance. */
        const val BLINK_DIM = 0.2f

        /**
         * How bright an invisible dot's blink rises to.
         *
         * The value is a transient peak, never written back to the configured
         * opacity, so the dot returns to invisible once the blink ends.
         */
        const val BLINK_PEAK_INVISIBLE = 0.5f

        /** Half of a fade-out/fade-in pair. */
        const val FADE_MS = 150L

        /** Below this the position is swapped while the dot is at its dimmest. */
        const val FADE_SWAP_AT = 0.2f

        /**
         * An interval at or under this skips the fade.
         *
         * Two fades take 300 ms, which at a one-second interval is most of the
         * time the dot has in one place.
         */
        const val FADE_THRESHOLD_MS = 1_500L

        /** 1/sqrt(2), for splitting a diagonal into its two components. */
        const val SQRT_2 = 1.4142135f

        /**
         * Ring radius as a multiple of the dot's diameter.
         *
         * 1/(2·sin(22.5°)) ≈ 1.3066 is the exact minimum for eight evenly
         * spaced positions to stay a diameter apart; the extra margin absorbs
         * the pixel rounding below.
         */
        const val RING_RADIUS_FACTOR = 1.32f

        /**
         * The ring of positions, walked in order.
         *
         * Diagonals come between the axes so consecutive steps are adjacent on
         * screen rather than jumping across the anchor.
         */
        val OFFSETS = listOf(
            0 to -1,
            1 to -1,
            1 to 0,
            1 to 1,
            0 to 1,
            -1 to 1,
            -1 to 0,
            -1 to -1,
        )
    }
}

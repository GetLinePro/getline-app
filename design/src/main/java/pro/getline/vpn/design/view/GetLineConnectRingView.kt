package pro.getline.vpn.design.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import pro.getline.vpn.design.R

/**
 * Connection state as a ring: the one animated element in the product UI, so
 * motion means work is happening rather than decoration.
 *
 * Draws the ring only — status and traffic are real TextViews centred over it,
 * which keeps them selectable and readable by TalkBack.
 */
class GetLineConnectRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    enum class State {
        Disconnected,
        Connecting,
        Connected,
    }

    private val strokeWidthPx =
        resources.getDimensionPixelSize(R.dimen.get_line_connect_ring_stroke).toFloat()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.getline_hairline)
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
    }

    private val bounds = RectF()
    private var state = State.Disconnected
    private var sweepRotation = 0f
    private var running = false
    private var lastFrameNs = 0L

    private val frameCallback = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            if (lastFrameNs != 0L) {
                val dt = ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
                sweepRotation = (sweepRotation + dt * SPIN_DEGREES_PER_SECOND) % 360f
            }
            lastFrameNs = now
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setState(value: State) {
        if (state == value) return
        state = value
        // A stopped spinner must not freeze mid-rotation and read as stuck.
        if (value != State.Connecting) sweepRotation = 0f
        updateAnimationState()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokeWidthPx / 2f
        val size = minOf(w, h).toFloat()
        val left = (w - size) / 2f + inset
        val top = (h - size) / 2f + inset
        bounds.set(left, top, left + size - strokeWidthPx, top + size - strokeWidthPx)

        // A sweep spanning the full circle needs matching ends or it seams.
        arcPaint.shader = SweepGradient(
            bounds.centerX(),
            bounds.centerY(),
            intArrayOf(
                ContextCompat.getColor(context, R.color.getline_brand_gradient_start),
                ContextCompat.getColor(context, R.color.getline_brand_gradient_end),
                ContextCompat.getColor(context, R.color.getline_brand_gradient_start),
            ),
            floatArrayOf(0f, 0.5f, 1f),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimationState()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimationState()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimationState()
    }

    override fun onDraw(canvas: Canvas) {
        if (bounds.isEmpty) return

        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)

        when (state) {
            // Track only: nothing is running, so nothing is highlighted.
            State.Disconnected -> Unit
            State.Connecting -> canvas.drawArc(
                bounds,
                sweepRotation - 90f,
                CONNECTING_SWEEP_DEGREES,
                false,
                arcPaint,
            )
            State.Connected -> canvas.drawArc(bounds, -90f, 360f, false, arcPaint)
        }
    }

    private fun updateAnimationState() {
        val wantRun = state == State.Connecting &&
            isAttachedToWindow &&
            windowVisibility == VISIBLE &&
            visibility == VISIBLE &&
            animationsEnabled()
        if (wantRun) startAnimation() else stopAnimation()
    }

    private fun startAnimation() {
        if (running) return
        running = true
        lastFrameNs = 0L
        postOnAnimation(frameCallback)
    }

    private fun stopAnimation() {
        if (!running) return
        running = false
        removeCallbacks(frameCallback)
        lastFrameNs = 0L
    }

    /** Under reduced motion the connecting arc stays put instead of spinning. */
    private fun animationsEnabled(): Boolean {
        return try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        } catch (_: Settings.SettingNotFoundException) {
            true
        } catch (_: SecurityException) {
            true
        }
    }

    private companion object {
        const val CONNECTING_SWEEP_DEGREES = 90f
        const val SPIN_DEGREES_PER_SECOND = 220f
    }
}

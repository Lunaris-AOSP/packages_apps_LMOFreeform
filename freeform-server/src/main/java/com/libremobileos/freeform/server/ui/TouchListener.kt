package com.libremobileos.freeform.server.ui

import android.annotation.SuppressLint
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.libremobileos.freeform.server.LMOFreeformServiceHolder
import com.libremobileos.freeform.server.ui.FreeformWindow.WindowAction
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

class MoveTouchListener(
    private val window: FreeformWindow
) : View.OnTouchListener {
    private val touchSlop = ViewConfiguration.get(window.context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var windowStartX = 0
    private var windowStartY = 0
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val layout = window.freeformWindowView ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                windowStartX = window.windowParams.x
                windowStartY = window.windowParams.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - startX
                val deltaY = event.rawY - startY
                if (!dragging && hypot(deltaX, deltaY) <= touchSlop) return true
                dragging = true
                window.windowManager.updateViewLayout(layout, window.windowParams.apply {
                    x = windowStartX + deltaX.roundToInt()
                    y = windowStartY + deltaY.roundToInt()
                })
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) window.makeSureFreeformInScreen()
                else if (hypot(event.rawX - startX, event.rawY - startY) <= touchSlop) v.performClick()
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) window.makeSureFreeformInScreen()
        }
        return true
    }
}

class PillGestureController(
    private val window: FreeformWindow,
    private val pillView: View
) : View.OnTouchListener {
    companion object {
        private const val MIN_SWIPE_DP = 40
    }

    private val touchSlop = ViewConfiguration.get(pillView.context).scaledTouchSlop
    private val swipeThreshold = max(dp(MIN_SWIPE_DP), touchSlop)
    private var startX = 0f
    private var startY = 0f
    private var activeAction: WindowAction? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                window.freeformLayout?.animate()?.cancel()
                updateAction(null)
                animatePressed(true)
            }
            MotionEvent.ACTION_MOVE -> {
                updateInteractiveScale(event.rawY - startY)
                updateAction(swipeAction(event.rawX - startX, event.rawY - startY))
            }
            MotionEvent.ACTION_UP -> {
                val deltaX = event.rawX - startX
                val deltaY = event.rawY - startY
                animatePressed(false)
                when (swipeAction(deltaX, deltaY)) {
                    WindowAction.Close -> animateClose()
                    WindowAction.Fullscreen -> animateFullscreen()
                    else -> {
                        animateBackToIdle()
                        if (abs(deltaX) < touchSlop && abs(deltaY) < touchSlop) v.performClick()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                animatePressed(false)
                animateBackToIdle()
            }
        }
        return true
    }

    private fun swipeAction(deltaX: Float, deltaY: Float): WindowAction? = when {
        abs(deltaY) <= abs(deltaX) -> null
        deltaY < -swipeThreshold -> WindowAction.Close
        deltaY > swipeThreshold -> WindowAction.Fullscreen
        else -> null
    }

    private fun updateAction(action: WindowAction?) {
        if (action == activeAction) return
        activeAction = action
        if (action != null) pillView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        window.showActionIcon(action)
    }

    private fun updateInteractiveScale(deltaY: Float) {
        val layout = window.freeformLayout ?: return
        val scaleDistance = max(layout.height, dp(180)).toFloat()
        val scale = max(0.01f, 1f + deltaY / scaleDistance)
        updateWindowHostBounds(scale)

        layout.pivotX = layout.width / 2f
        layout.pivotY = layout.height.toFloat()
        layout.scaleX = scale
        layout.scaleY = scale
        layout.translationY = if (scale < 1f) deltaY * 0.12f else 0f
        layout.alpha = scale.coerceAtMost(1f)
    }

    private fun updateWindowHostBounds(scale: Float) {
        val layout = window.freeformLayout ?: return
        val host = window.freeformWindowView ?: return
        val expandedScale = max(scale, 1f)
        val width = ceil(layout.width * expandedScale).roundToInt()
        val height = ceil(layout.height * expandedScale).roundToInt()
        if (window.windowParams.width == width && window.windowParams.height == height) return
        window.windowParams.width = width
        window.windowParams.height = height
        runCatching { window.windowManager.updateViewLayout(host, window.windowParams) }
    }

    private fun resetWindowHostBounds() {
        val host = window.freeformWindowView ?: return
        window.windowParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        window.windowParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        runCatching { window.windowManager.updateViewLayout(host, window.windowParams) }
    }

    private fun animateClose() {
        val layout = window.freeformLayout ?: return
        updateAction(WindowAction.Close)
        layout.animate()
            .translationYBy(-dp(24).toFloat())
            .alpha(0f)
            .scaleX(0.01f)
            .scaleY(0.01f)
            .setDuration(120L)
            .withEndAction {
                resetWindowHostBounds()
                window.close()
            }
            .start()
    }

    private fun animateFullscreen() {
        val layout = window.freeformLayout ?: return
        updateAction(WindowAction.Fullscreen)
        val targetScale = max(layout.scaleX, 1.12f)
        updateWindowHostBounds(targetScale)
        layout.animate()
            .alpha(1f)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(90L)
            .withEndAction {
                window.enterFullscreen()
                animateBackToIdle()
            }
            .start()
    }

    private fun animateBackToIdle() {
        val layout = window.freeformLayout ?: return
        updateAction(null)
        layout.animate()
            .translationY(0f)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180L)
            .withEndAction { resetWindowHostBounds() }
            .start()
    }

    private fun animatePressed(pressed: Boolean) {
        pillView.animate()
            .alpha(if (pressed) 1f else 0.8f)
            .scaleX(if (pressed) 1.15f else 1f)
            .setDuration(120L)
            .start()
    }

    private fun dp(value: Int): Int = (value * pillView.resources.displayMetrics.density).roundToInt()
}

class ScaleTouchListener(private val window: FreeformWindow, private val isRight: Boolean = true): View.OnTouchListener {
    private var startX = 0.0f
    private var startY = 0.0f
    private var isPinTriggered = false

    companion object {
        private const val PIN_THRESHOLD_DP = 160
        private const val MIN_WINDOW_SIZE_DP = 110
    }

    private fun dp(value: Int): Int =
        (value * window.context.resources.displayMetrics.density).roundToInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val layout = window.freeformLayout ?: return true
        val veilView = window.veilView ?: return true
        val freeformView = window.freeformView ?: return true
        
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                isPinTriggered = false
                freeformView.visibility = View.INVISIBLE
                veilView.visibility = View.VISIBLE
            }
            MotionEvent.ACTION_MOVE -> {
                val xDelta = if (isRight) (event.rawX - startX) else (startX - event.rawX)
                val yDelta = event.rawY - startY

                val currentWidth = layout.layoutParams.width
                val currentHeight = layout.layoutParams.height
                val maxWidth = window.defaultDisplayWidth
                val maxHeight = (window.defaultDisplayHeight * 0.9).roundToInt()

                val pinThresholdWidth = max(dp(PIN_THRESHOLD_DP), window.freeformConfig.hangUpWidth)
                val pinThresholdHeight = max(dp(PIN_THRESHOLD_DP), window.freeformConfig.hangUpHeight)
                val minAllowedWidth = max(dp(MIN_WINDOW_SIZE_DP), (pinThresholdWidth * 0.75f).roundToInt())
                val minAllowedHeight = max(dp(MIN_WINDOW_SIZE_DP), (pinThresholdHeight * 0.75f).roundToInt())

                val effXDelta = if (currentWidth <= pinThresholdWidth && xDelta < 0) xDelta * 0.3f else xDelta
                val effYDelta = if (currentHeight <= pinThresholdHeight && yDelta < 0) yDelta * 0.3f else yDelta

                var targetWidth = (currentWidth + effXDelta).roundToInt()
                var targetHeight = (currentHeight + effYDelta).roundToInt()

                if (targetWidth > targetHeight) {
                    if (xDelta < 0) targetWidth = targetHeight
                    else targetHeight = targetWidth
                }

                targetWidth = targetWidth.coerceIn(minAllowedWidth.coerceAtMost(maxWidth), maxWidth)
                targetHeight = targetHeight.coerceIn(minAllowedHeight.coerceAtMost(maxHeight), maxHeight)
                window.setWindowSize(targetWidth, targetHeight)

                val shouldTriggerPin = targetWidth <= pinThresholdWidth || targetHeight <= pinThresholdHeight
                if (shouldTriggerPin != isPinTriggered) {
                    isPinTriggered = shouldTriggerPin
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    window.showActionIcon(if (isPinTriggered) WindowAction.Pin else null)
                }

                startX = event.rawX
                startY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                if (isPinTriggered) {
                    isPinTriggered = false
                    window.showActionIcon(null)
                    freeformView.visibility = View.VISIBLE
                    veilView.visibility = View.GONE
                    window.handler.post { window.handleHangUp() }
                    return true
                }

                val surfaceTexture = freeformView.surfaceTexture
                if (surfaceTexture == null) {
                    window.restoreWindowSize()
                    freeformView.visibility = View.VISIBLE
                    veilView.visibility = View.GONE
                } else {
                    window.freeformConfig.width = layout.layoutParams.width
                    window.freeformConfig.height = layout.layoutParams.height
                    window.makeSureFreeformInScreen()
                    window.measureScale()
                    LMOFreeformServiceHolder.resizeFreeform(
                        window,
                        window.freeformConfig.freeformWidth,
                        window.freeformConfig.freeformHeight,
                        window.freeformConfig.densityDpi
                    )
                    surfaceTexture.setDefaultBufferSize(window.freeformConfig.freeformWidth, window.freeformConfig.freeformHeight)
                    // Delay the unveiling until after the scaling is complete
                    window.handler.postDelayed({
                        freeformView.visibility = View.VISIBLE
                        veilView.visibility = View.GONE
                    }, 250)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                isPinTriggered = false
                window.showActionIcon(null)
                window.restoreWindowSize()
                freeformView.visibility = View.VISIBLE
                veilView.visibility = View.GONE
            }
        }
        return true
    }
}

class HangUpGestureListener(private val window: FreeformWindow) : SimpleOnGestureListener() {
    private var startX = 0
    private var startY = 0
    override fun onDown(e: MotionEvent): Boolean {
        startX = window.windowParams.x
        startY = window.windowParams.y
        return super.onDown(e)
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        window.handler.post { window.handleHangUp() }
        return true
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (!isValidMotionEvent(e1) || !isValidMotionEvent(e2)) {
            return true
        }

        val e1RawX = e1?.rawX ?: 0f
        val e1RawY = e1?.rawY ?: 0f

        if (!isValidCoordinate(e1RawX) || !isValidCoordinate(e1RawY) 
                || !isValidCoordinate(e2.rawX) || !isValidCoordinate(e2.rawY)) {
            return true
        }
        
        val newX = (startX + e2.rawX - e1RawX).roundToInt()
        val newY = (startY + e2.rawY - e1RawY).roundToInt()

        try {
            window.handler.post {
                val layout = window.freeformWindowView ?: window.freeformLayout ?: return@post
                window.windowManager.updateViewLayout(layout, window.windowParams.apply {
                    x = newX
                    y = newY
                })
            }
        } catch (e: Exception) {}
        return true
    }

    fun isValidMotionEvent(event: MotionEvent?): Boolean {
        return event != null &&
                !event.rawX.isNaN() &&
                !event.rawY.isNaN() &&
                event.rawX.isFinite() &&
                event.rawY.isFinite()
    }
    
    fun isValidCoordinate(coordinate: Float): Boolean {
        return !coordinate.isNaN() && coordinate.isFinite()
    }
}

package com.livetranslate.app.overlay

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.livetranslate.app.data.UserSettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Floating subtitle window using classic Views (reliable with WindowManager).
 * - thin top grabber to move
 * - bottom-right handle to resize box only (font size unchanged)
 * - clamps size/position on orientation change so the handle never goes off-screen
 */
class SubtitleOverlayController(
    private val context: Context,
    private val onGeometryChanged: (x: Int, y: Int, widthDp: Int, heightDp: Int) -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var inputView: TextView? = null
    private var outputView: TextView? = null
    private var container: LinearLayout? = null

    private var settings: UserSettings = UserSettings()
    private var inputText: String = ""
    private var outputText: String = ""

    private var lastScreenW = 0
    private var lastScreenH = 0

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            // Orientation / size class changed — clamp overlay into new bounds.
            clampAndApply(persist = true, reason = "config")
        }

        override fun onLowMemory() = Unit
    }

    private var callbacksRegistered = false

    fun show(initial: UserSettings) {
        if (rootView != null) {
            updateSettings(initial)
            clampAndApply(persist = true, reason = "show-update")
            return
        }
        settings = initial

        val (screenW, screenH, density) = screenMetrics()
        lastScreenW = screenW
        lastScreenH = screenH

        val widthPx = clampWidth((initial.overlayWidthDp * density).roundToInt(), screenW)
        val heightPx = clampHeight((initial.overlayHeightDp * density).roundToInt(), screenH)
        val x = if (initial.overlayX < 0) {
            ((screenW - widthPx) / 2).coerceAtLeast(0)
        } else {
            safeCoerce(initial.overlayX, 0, max(0, screenW - widthPx))
        }
        val y = if (initial.overlayY < 0) {
            (screenH * 0.72f).roundToInt().let { safeCoerce(it, 0, max(0, screenH - heightPx)) }
        } else {
            safeCoerce(initial.overlayY, 0, max(0, screenH - heightPx))
        }

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        layoutParams = params

        val view = buildOverlayView(density)
        rootView = view
        windowManager.addView(view, params)
        registerCallbacks()
        applySettingsToViews()
        applyTranscriptsToViews()
        // Persist clamped geometry in case saved values were out of range.
        persistGeometry()
    }

    fun updateSettings(value: UserSettings) {
        settings = value
        applySettingsToViews()
        // Style-only updates should not fight live drag geometry unless size keys changed
        // and we are not currently oversized for the screen.
        clampAndApply(persist = false, reason = "settings")
    }

    fun updateTranscripts(input: String?, output: String?) {
        if (input != null) inputText = input
        if (output != null) outputText = output
        applyTranscriptsToViews()
    }

    fun hide() {
        unregisterCallbacks()
        val view = rootView ?: return
        runCatching { windowManager.removeView(view) }
        rootView = null
        layoutParams = null
        inputView = null
        outputView = null
        container = null
    }

    /**
     * Ensure width/height fit the current screen, then clamp x/y so the whole box stays on-screen.
     * Prevents IllegalArgumentException from coerceIn(0, negative) when box is larger than screen.
     */
    private fun clampAndApply(persist: Boolean, reason: String) {
        val params = layoutParams ?: return
        val view = rootView ?: return
        val (screenW, screenH, _) = screenMetrics()

        val screenChanged = screenW != lastScreenW || screenH != lastScreenH
        lastScreenW = screenW
        lastScreenH = screenH

        val oldW = params.width
        val oldH = params.height
        val oldX = params.x
        val oldY = params.y

        params.width = clampWidth(params.width, screenW)
        params.height = clampHeight(params.height, screenH)
        params.x = safeCoerce(params.x, 0, max(0, screenW - params.width))
        params.y = safeCoerce(params.y, 0, max(0, screenH - params.height))

        val changed = params.width != oldW || params.height != oldH ||
            params.x != oldX || params.y != oldY || screenChanged

        if (changed) {
            Log.i(
                TAG,
                "clamp($reason): ${oldW}x${oldH}@${oldX},${oldY} -> " +
                    "${params.width}x${params.height}@${params.x},${params.y} screen=${screenW}x${screenH}",
            )
            runCatching { windowManager.updateViewLayout(view, params) }
                .onFailure { Log.e(TAG, "updateViewLayout failed", it) }
            if (persist || screenChanged) {
                persistGeometry()
            }
        }
    }

    private fun applyLayoutSafe() {
        val params = layoutParams ?: return
        val view = rootView ?: return
        val (screenW, screenH, _) = screenMetrics()
        params.width = clampWidth(params.width, screenW)
        params.height = clampHeight(params.height, screenH)
        params.x = safeCoerce(params.x, 0, max(0, screenW - params.width))
        params.y = safeCoerce(params.y, 0, max(0, screenH - params.height))
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { Log.e(TAG, "updateViewLayout failed", it) }
    }

    private fun screenMetrics(): Triple<Int, Int, Float> {
        val density = context.resources.displayMetrics.density
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), density)
        } else {
            val dm = context.resources.displayMetrics
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val real = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(real)
            Triple(real.widthPixels, real.heightPixels, density)
        }
    }

    private fun clampWidth(width: Int, screenW: Int): Int {
        val minW = min(MIN_WIDTH_PX, screenW)
        val maxW = max(minW, screenW - EDGE_MARGIN_PX)
        return safeCoerce(width, minW, maxW)
    }

    private fun clampHeight(height: Int, screenH: Int): Int {
        val minH = min(MIN_HEIGHT_PX, screenH)
        // At most half screen, but never more than screen - margin
        val maxH = max(minH, min(screenH / 2, screenH - EDGE_MARGIN_PX))
        return safeCoerce(height, minH, maxH)
    }

    /** coerceIn that never throws when end < start */
    private fun safeCoerce(value: Int, start: Int, end: Int): Int {
        if (end < start) return start
        return value.coerceIn(start, end)
    }

    private fun registerCallbacks() {
        if (callbacksRegistered) return
        runCatching { context.applicationContext.registerComponentCallbacks(configCallbacks) }
        callbacksRegistered = true
    }

    private fun unregisterCallbacks() {
        if (!callbacksRegistered) return
        runCatching { context.applicationContext.unregisterComponentCallbacks(configCallbacks) }
        callbacksRegistered = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlayView(density: Float): View {
        val root = FrameLayout(context)

        val bg = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(Color.argb((settings.backgroundAlpha * 255).toInt().coerceIn(25, 242), 0, 0, 0))
        }
        root.background = bg
        val pad = (10 * density).roundToInt()
        root.setPadding(pad, (6 * density).roundToInt(), pad, pad)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        container = column

        // Thin grabber
        val grabberRow = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (22 * density).roundToInt(),
            )
        }
        val grabberBar = View(context).apply {
            val w = (36 * density).roundToInt()
            val h = (4 * density).roundToInt()
            layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            background = GradientDrawable().apply {
                cornerRadius = 2 * density
                setColor(Color.argb(115, 255, 255, 255))
            }
        }
        grabberRow.addView(grabberBar)
        grabberRow.setOnTouchListener(MoveTouchListener())
        column.addView(grabberRow)

        val input = TextView(context).apply {
            setTextColor(Color.argb(191, 255, 255, 255))
            typeface = Typeface.DEFAULT
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        inputView = input
        column.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val output = TextView(context).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT
            maxLines = 8
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "…"
        }
        outputView = output
        column.addView(
            output,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        root.addView(column)

        // Resize handle (啾啾)
        val handleSize = (22 * density).roundToInt()
        val handle = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(handleSize, handleSize, Gravity.BOTTOM or Gravity.END)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(90, 255, 255, 255))
            }
        }
        handle.setOnTouchListener(ResizeTouchListener())
        root.addView(handle)

        return root
    }

    private fun applySettingsToViews() {
        val alpha = (settings.backgroundAlpha * 255).toInt().coerceIn(25, 242)
        (rootView?.background as? GradientDrawable)?.setColor(Color.argb(alpha, 0, 0, 0))
        inputView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp * 0.9f)
        outputView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp)
        inputView?.visibility = if (settings.bilingual) View.VISIBLE else View.GONE
    }

    private fun applyTranscriptsToViews() {
        inputView?.text = inputText
        outputView?.text = outputText.ifBlank { "…" }
        inputView?.visibility = if (settings.bilingual) View.VISIBLE else View.GONE
    }

    private fun persistGeometry() {
        val params = layoutParams ?: return
        val d = context.resources.displayMetrics.density.coerceAtLeast(0.5f)
        runCatching {
            onGeometryChanged(
                params.x,
                params.y,
                (params.width / d).roundToInt().coerceAtLeast(1),
                (params.height / d).roundToInt().coerceAtLeast(1),
            )
        }.onFailure { Log.e(TAG, "persistGeometry failed", it) }
    }

    private inner class MoveTouchListener : View.OnTouchListener {
        private var lastX = 0f
        private var lastY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            val root = rootView ?: return false
            return try {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Fix bad geometry before drag starts (e.g. after rotation).
                        clampAndApply(persist = false, reason = "move-down")
                        lastX = event.rawX
                        lastY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY
                        lastX = event.rawX
                        lastY = event.rawY
                        val (screenW, screenH, _) = screenMetrics()
                        // Always clamp size first so maxX/maxY are non-negative.
                        params.width = clampWidth(params.width, screenW)
                        params.height = clampHeight(params.height, screenH)
                        params.x = safeCoerce(
                            params.x + dx.roundToInt(),
                            0,
                            max(0, screenW - params.width),
                        )
                        params.y = safeCoerce(
                            params.y + dy.roundToInt(),
                            0,
                            max(0, screenH - params.height),
                        )
                        windowManager.updateViewLayout(root, params)
                        persistGeometry()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        clampAndApply(persist = true, reason = "move-up")
                        true
                    }
                    else -> false
                }
            } catch (t: Throwable) {
                Log.e(TAG, "move touch failed", t)
                clampAndApply(persist = true, reason = "move-error")
                true
            }
        }
    }

    private inner class ResizeTouchListener : View.OnTouchListener {
        private var lastX = 0f
        private var lastY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            val root = rootView ?: return false
            return try {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        clampAndApply(persist = false, reason = "resize-down")
                        lastX = event.rawX
                        lastY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY
                        lastX = event.rawX
                        lastY = event.rawY
                        val (screenW, screenH, _) = screenMetrics()
                        // Grow/shrink from top-left anchor; keep x,y and clamp size into remaining space.
                        val maxW = max(MIN_WIDTH_PX, screenW - params.x - EDGE_MARGIN_PX)
                        val maxH = max(MIN_HEIGHT_PX, min(screenH / 2, screenH - params.y - EDGE_MARGIN_PX))
                        params.width = safeCoerce(params.width + dx.roundToInt(), MIN_WIDTH_PX, maxW)
                        params.height = safeCoerce(params.height + dy.roundToInt(), MIN_HEIGHT_PX, maxH)
                        // Re-clamp position in case size change needs it
                        params.x = safeCoerce(params.x, 0, max(0, screenW - params.width))
                        params.y = safeCoerce(params.y, 0, max(0, screenH - params.height))
                        windowManager.updateViewLayout(root, params)
                        persistGeometry()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        clampAndApply(persist = true, reason = "resize-up")
                        true
                    }
                    else -> false
                }
            } catch (t: Throwable) {
                Log.e(TAG, "resize touch failed", t)
                clampAndApply(persist = true, reason = "resize-error")
                true
            }
        }
    }

    companion object {
        private const val TAG = "SubtitleOverlay"
        private const val MIN_WIDTH_PX = 200
        private const val MIN_HEIGHT_PX = 80
        private const val EDGE_MARGIN_PX = 8
    }
}

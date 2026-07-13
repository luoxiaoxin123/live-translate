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
import android.widget.ScrollView
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
 * - bilingual: source + divider + translation, each with independent auto-scroll
 * - translation-only: single auto-scrolling pane
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
    private var inputScroll: ScrollView? = null
    private var outputScroll: ScrollView? = null
    private var dividerView: View? = null
    private var inputSection: LinearLayout? = null
    private var container: LinearLayout? = null

    private var settings: UserSettings = UserSettings()
    private var inputText: String = ""
    private var outputText: String = ""

    private var lastScreenW = 0
    private var lastScreenH = 0

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
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
        persistGeometry()
    }

    fun updateSettings(value: UserSettings) {
        settings = value
        applySettingsToViews()
        applyLayoutMode()
        clampAndApply(persist = false, reason = "settings")
        // After mode switch, re-apply text + scroll
        applyTranscriptsToViews()
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
        inputScroll = null
        outputScroll = null
        dividerView = null
        inputSection = null
        container = null
    }

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

    private fun screenMetrics(): Triple<Int, Int, Float> {
        val density = context.resources.displayMetrics.density
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), density)
        } else {
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
        val maxH = max(minH, min(screenH / 2, screenH - EDGE_MARGIN_PX))
        return safeCoerce(height, minH, maxH)
    }

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
        val padH = (10 * density).roundToInt()
        val padV = (6 * density).roundToInt()
        root.setPadding(padH, padV, padH, padH)

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

        // ---- Source pane (bilingual only) ----
        val sourceSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            visibility = if (settings.bilingual) View.VISIBLE else View.GONE
        }
        inputSection = sourceSection

        val inScroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // Don't steal drag from grabber/resize; text area is display-only scroll
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        inputScroll = inScroll

        val input = TextView(context).apply {
            setTextColor(Color.argb(200, 255, 255, 255))
            typeface = Typeface.DEFAULT
            setLineSpacing(0f, 1.15f)
            // No maxLines — grow and scroll
            text = ""
        }
        inputView = input
        inScroll.addView(
            input,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        sourceSection.addView(inScroll)
        column.addView(sourceSection)

        // Divider between source and translation
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                max(1, (1 * density).roundToInt()),
            ).apply {
                topMargin = (4 * density).roundToInt()
                bottomMargin = (4 * density).roundToInt()
            }
            setBackgroundColor(Color.argb(70, 255, 255, 255))
            visibility = if (settings.bilingual) View.VISIBLE else View.GONE
        }
        dividerView = divider
        column.addView(divider)

        // ---- Translation pane (always) ----
        val outScroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        outputScroll = outScroll

        val output = TextView(context).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT
            setLineSpacing(0f, 1.15f)
            text = "…"
        }
        outputView = output
        outScroll.addView(
            output,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        column.addView(outScroll)

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

        applyLayoutMode()
        return root
    }

    /** Switch bilingual (split + divider) vs translation-only (full height scroll). */
    private fun applyLayoutMode() {
        val bilingual = settings.bilingual
        inputSection?.visibility = if (bilingual) View.VISIBLE else View.GONE
        dividerView?.visibility = if (bilingual) View.VISIBLE else View.GONE

        // When translation-only, output takes all remaining weight.
        // When bilingual, both panes share weight 1f each.
        (inputSection?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.weight = 1f
            lp.height = 0
            inputSection?.layoutParams = lp
        }
        (outputScroll?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.weight = 1f
            lp.height = 0
            outputScroll?.layoutParams = lp
        }
    }

    private fun applySettingsToViews() {
        val alpha = (settings.backgroundAlpha * 255).toInt().coerceIn(25, 242)
        (rootView?.background as? GradientDrawable)?.setColor(Color.argb(alpha, 0, 0, 0))
        inputView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp * 0.9f)
        outputView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp)
    }

    private fun applyTranscriptsToViews() {
        val inTv = inputView
        val outTv = outputView
        val inSc = inputScroll
        val outSc = outputScroll

        if (inTv != null) {
            val next = inputText
            if (inTv.text?.toString() != next) {
                inTv.text = next
                scrollToBottom(inSc)
            }
        }
        if (outTv != null) {
            val next = outputText.ifBlank { "…" }
            if (outTv.text?.toString() != next) {
                outTv.text = next
                scrollToBottom(outSc)
            }
        }
    }

    /** Auto-scroll to the latest line after layout. */
    private fun scrollToBottom(scrollView: ScrollView?) {
        if (scrollView == null) return
        scrollView.post {
            // fullScroll is reliable after text layout; post again if content still growing
            scrollView.fullScroll(View.FOCUS_DOWN)
            scrollView.post {
                val child = scrollView.getChildAt(0) ?: return@post
                val bottom = (child.bottom + scrollView.paddingBottom) - scrollView.height
                if (bottom > 0) {
                    scrollView.scrollTo(0, bottom)
                }
            }
        }
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
                        val maxW = max(MIN_WIDTH_PX, screenW - params.x - EDGE_MARGIN_PX)
                        val maxH = max(
                            MIN_HEIGHT_PX,
                            min(screenH / 2, screenH - params.y - EDGE_MARGIN_PX),
                        )
                        params.width = safeCoerce(params.width + dx.roundToInt(), MIN_WIDTH_PX, maxW)
                        params.height = safeCoerce(params.height + dy.roundToInt(), MIN_HEIGHT_PX, maxH)
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

package com.livetranslate.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.livetranslate.app.data.UserSettings
import kotlin.math.roundToInt

/**
 * Floating subtitle window using classic Views (reliable with WindowManager).
 * - thin top grabber to move
 * - bottom-right handle to resize box only (font size unchanged)
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

    fun show(initial: UserSettings) {
        if (rootView != null) {
            updateSettings(initial)
            return
        }
        settings = initial

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val density = metrics.density
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val widthPx = (initial.overlayWidthDp * density).roundToInt().coerceIn(200, screenW)
        val heightPx = (initial.overlayHeightDp * density).roundToInt().coerceIn(80, screenH / 2)
        val x = initial.overlayX.coerceIn(0, (screenW - widthPx).coerceAtLeast(0))
        val y = if (initial.overlayY < 0) {
            (screenH * 0.72f).roundToInt()
        } else {
            initial.overlayY.coerceIn(0, (screenH - heightPx).coerceAtLeast(0))
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
        applySettingsToViews()
        applyTranscriptsToViews()
    }

    fun updateSettings(value: UserSettings) {
        settings = value
        applySettingsToViews()
        val params = layoutParams ?: return
        val view = rootView ?: return
        val metrics = context.resources.displayMetrics
        val widthPx = (value.overlayWidthDp * metrics.density).roundToInt()
            .coerceIn(200, metrics.widthPixels)
        val heightPx = (value.overlayHeightDp * metrics.density).roundToInt()
            .coerceIn(80, metrics.heightPixels / 2)
        if (params.width != widthPx || params.height != heightPx) {
            params.width = widthPx
            params.height = heightPx
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    fun updateTranscripts(input: String?, output: String?) {
        if (input != null) inputText = input
        if (output != null) outputText = output
        applyTranscriptsToViews()
    }

    fun hide() {
        val view = rootView ?: return
        runCatching { windowManager.removeView(view) }
        rootView = null
        layoutParams = null
        inputView = null
        outputView = null
        container = null
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
            layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER).apply {
                // center
            }
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
        val density = context.resources.displayMetrics.density
        val alpha = (settings.backgroundAlpha * 255).toInt().coerceIn(25, 242)
        (rootView?.background as? GradientDrawable)?.setColor(Color.argb(alpha, 0, 0, 0))
        inputView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp * 0.9f)
        outputView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp)
        inputView?.visibility = if (settings.bilingual) View.VISIBLE else View.GONE
        // density used only for potential future padding tweaks
        @Suppress("UNUSED_VARIABLE")
        val ignored = density
    }

    private fun applyTranscriptsToViews() {
        inputView?.text = inputText
        outputView?.text = outputText.ifBlank { "…" }
        inputView?.visibility = if (settings.bilingual && inputText.isNotBlank()) {
            View.VISIBLE
        } else if (settings.bilingual) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun persistGeometry() {
        val params = layoutParams ?: return
        val d = context.resources.displayMetrics.density
        onGeometryChanged(
            params.x,
            params.y,
            (params.width / d).roundToInt(),
            (params.height / d).roundToInt(),
        )
    }

    private inner class MoveTouchListener : View.OnTouchListener {
        private var lastX = 0f
        private var lastY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            val root = rootView ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    lastX = event.rawX
                    lastY = event.rawY
                    val metrics = context.resources.displayMetrics
                    params.x = (params.x + dx.roundToInt())
                        .coerceIn(0, metrics.widthPixels - params.width)
                    params.y = (params.y + dy.roundToInt())
                        .coerceIn(0, metrics.heightPixels - params.height)
                    windowManager.updateViewLayout(root, params)
                    persistGeometry()
                    return true
                }
            }
            return false
        }
    }

    private inner class ResizeTouchListener : View.OnTouchListener {
        private var lastX = 0f
        private var lastY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            val root = rootView ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    lastX = event.rawX
                    lastY = event.rawY
                    val metrics = context.resources.displayMetrics
                    params.width = (params.width + dx.roundToInt())
                        .coerceIn(200, metrics.widthPixels)
                    params.height = (params.height + dy.roundToInt())
                        .coerceIn(80, metrics.heightPixels / 2)
                    windowManager.updateViewLayout(root, params)
                    persistGeometry()
                    return true
                }
            }
            return false
        }
    }
}

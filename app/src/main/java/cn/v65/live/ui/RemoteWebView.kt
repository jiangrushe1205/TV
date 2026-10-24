package cn.v65.live.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

/** Remote-control pointer for official page controls that do not expose keyboard focus. */
class RemoteWebView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : WebView(context, attrs) {
    var pointerEnabled = false
        set(value) {
            field = value
            if (value) { cursorX = width / 2f; cursorY = height / 2f }
            val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (!value) input.hideSoftInputFromWindow(windowToken, 0)
            input.restartInput(this)
            invalidate()
        }
    // The official page may focus a search field while live TV is playing.
    // Only deliberate webpage-operation mode should open the TV keyboard.
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        if (pointerEnabled) super.onCreateInputConnection(outAttrs) else null
    private var cursorX = -1f
    private var cursorY = -1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var onToolbar: (() -> Unit)? = null
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cursorX < 0) { cursorX = w / 2f; cursorY = h / 2f }
        cursorX = cursorX.coerceIn(0f, w.toFloat()); cursorY = cursorY.coerceIn(0f, h.toFloat())
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!pointerEnabled) return super.onKeyDown(keyCode, event)
        val step = resources.displayMetrics.density * if (event.repeatCount > 4) 36f else 22f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorX -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX += step
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (cursorY <= step && scrollY <= 0) { onToolbar?.invoke(); return true }
                if (cursorY <= step) scrollBy(0, -step.toInt()) else cursorY -= step
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (cursorY >= height - step) scrollBy(0, step.toInt()) else cursorY += step
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) {
                    val now = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
                    val up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
                    onTouchEvent(down); onTouchEvent(up); down.recycle(); up.recycle()
                }
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        cursorX = cursorX.coerceIn(8f, (width - 8f).coerceAtLeast(8f))
        cursorY = cursorY.coerceIn(8f, (height - 8f).coerceAtLeast(8f))
        invalidate()
        return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = if (pointerEnabled && keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) true else super.onKeyUp(keyCode, event)
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!pointerEnabled || !hasFocus()) return
        val radius = resources.displayMetrics.density * 7
        paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density * 4
        canvas.drawCircle(cursorX, cursorY, radius, paint)
        paint.color = 0xFF70E4BA.toInt(); paint.strokeWidth = resources.displayMetrics.density * 2
        canvas.drawCircle(cursorX, cursorY, radius, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cursorX, cursorY, resources.displayMetrics.density * 2, paint)
    }
}

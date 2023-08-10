package com.android.systemui.shade

import android.view.MotionEvent
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel

private const val TAG = "systemui.shade.touch"

/**
 * A logger for tracking touch dispatching in the shade view hierarchy. The purpose of this logger
 * is to passively observe dispatchTouchEvent calls in order to see which subtrees of the shade are
 * handling touches. Additionally, some touches may be passively observed for views near the top of
 * the shade hierarchy that cannot intercept touches, i.e. scrims. The usage of static methods for
 * logging is sub-optimal in many ways, but it was selected in this case to make usage of this
 * non-function diagnostic code as low friction as possible.
 */
class TouchLogger {
    companion object {
        private var touchLogger: DispatchTouchLogger? = null

        @JvmStatic
        fun logTouchesTo(buffer: LogBuffer) {
            touchLogger = DispatchTouchLogger(buffer)
        }

        @JvmStatic
        fun logDispatchTouch(viewTag: String, ev: MotionEvent, result: Boolean): Boolean {
            touchLogger?.logDispatchTouch(viewTag, ev, result)
            return result
        }
    }
}

/** Logs touches. */
private class DispatchTouchLogger(private val buffer: LogBuffer) {
    fun logDispatchTouch(viewTag: String, ev: MotionEvent, result: Boolean) {
        // NOTE: never log position of touches for security purposes
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = viewTag
                int1 = ev.action
                long1 = ev.downTime
                bool1 = result
            },
            { "Touch: view=$str1, type=${typeToString(int1)}, downtime=$long1, result=$bool1" }
        )
    }

    private fun typeToString(type: Int): String {
        return when (type) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            else -> "OTHER"
        }
    }
}

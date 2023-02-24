package com.android.systemui.shade

import android.view.MotionEvent
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.LogMessage
import com.android.systemui.log.dagger.ShadeLog
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "systemui.shade"

/** Lightweight logging utility for the Shade. */
class ShadeLogger @Inject constructor(
    @ShadeLog
    private val buffer: LogBuffer
) {
    fun v(@CompileTimeConstant msg: String) {
        buffer.log(TAG, LogLevel.VERBOSE, msg)
    }

    private inline fun log(
        logLevel: LogLevel,
        initializer: LogMessage.() -> Unit,
        noinline printer: LogMessage.() -> String
    ) {
        buffer.log(TAG, logLevel, initializer, printer)
    }

    fun onQsInterceptMoveQsTrackingEnabled(h: Float) {
        log(LogLevel.VERBOSE,
            { double1 = h.toDouble() },
            { "onQsIn[tercept: move action, QS tracking enabled. h = $double1" })
    }

    fun logMotionEvent(event: MotionEvent, message: String) {
        log(LogLevel.VERBOSE, {
            str1 = message
            long1 = event.eventTime
            long2 = event.downTime
            int1 = event.action
            int2 = event.classification
            double1 = event.y.toDouble()
        }, {
            "$str1\neventTime=$long1,downTime=$long2,y=$double1,action=$int1,classification=$int2"
        })
    }
}

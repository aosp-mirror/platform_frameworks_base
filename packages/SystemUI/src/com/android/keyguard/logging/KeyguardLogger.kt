package com.android.keyguard.logging

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.LogLevel.DEBUG
import com.android.systemui.log.LogLevel.ERROR
import com.android.systemui.log.LogLevel.VERBOSE
import com.android.systemui.log.LogLevel.WARNING
import com.android.systemui.log.MessageInitializer
import com.android.systemui.log.MessagePrinter
import com.android.systemui.log.dagger.KeyguardLog
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "KeyguardLog"

class KeyguardLogger @Inject constructor(@KeyguardLog private val buffer: LogBuffer) {
    fun d(@CompileTimeConstant msg: String) = log(msg, DEBUG)

    fun e(@CompileTimeConstant msg: String) = log(msg, ERROR)

    fun v(@CompileTimeConstant msg: String) = log(msg, VERBOSE)

    fun w(@CompileTimeConstant msg: String) = log(msg, WARNING)

    fun log(msg: String, level: LogLevel) = buffer.log(TAG, level, msg)

    private fun debugLog(messageInitializer: MessageInitializer, messagePrinter: MessagePrinter) {
        buffer.log(TAG, DEBUG, messageInitializer, messagePrinter)
    }

    // TODO: remove after b/237743330 is fixed
    fun logStatusBarCalculatedAlpha(alpha: Float) {
        debugLog({ double1 = alpha.toDouble() }, { "Calculated new alpha: $double1" })
    }

    // TODO: remove after b/237743330 is fixed
    fun logStatusBarExplicitAlpha(alpha: Float) {
        debugLog({ double1 = alpha.toDouble() }, { "new mExplicitAlpha value: $double1" })
    }

    // TODO: remove after b/237743330 is fixed
    fun logStatusBarAlphaVisibility(visibility: Int, alpha: Float, state: String) {
        debugLog(
            {
                int1 = visibility
                double1 = alpha.toDouble()
                str1 = state
            },
            { "changing visibility to $int1 with alpha $double1 in state: $str1" }
        )
    }
}

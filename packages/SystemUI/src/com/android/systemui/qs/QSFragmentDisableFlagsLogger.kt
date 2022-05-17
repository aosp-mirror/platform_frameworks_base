package com.android.systemui.qs

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.QSFragmentDisableLog
import com.android.systemui.statusbar.DisableFlagsLogger
import javax.inject.Inject

/** A helper class for logging disable flag changes made in [QSFragment]. */
class QSFragmentDisableFlagsLogger @Inject constructor(
    @QSFragmentDisableLog private val buffer: LogBuffer,
    private val disableFlagsLogger: DisableFlagsLogger
) {

    /**
     * Logs a string representing the new state received by [QSFragment] and any modifications that
     * were made to the flags locally.
     *
     * @param new see [DisableFlagsLogger.getDisableFlagsString]
     * @param newAfterLocalModification see [DisableFlagsLogger.getDisableFlagsString]
     */
    fun logDisableFlagChange(
        new: DisableFlagsLogger.DisableState,
        newAfterLocalModification: DisableFlagsLogger.DisableState
    ) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                int1 = new.disable1
                int2 = new.disable2
                long1 = newAfterLocalModification.disable1.toLong()
                long2 = newAfterLocalModification.disable2.toLong()
            },
            {
                disableFlagsLogger.getDisableFlagsString(
                    old = null,
                    new = DisableFlagsLogger.DisableState(int1, int2),
                    newAfterLocalModification =
                        DisableFlagsLogger.DisableState(long1.toInt(), long2.toInt())
                )
            }
        )
    }
}

private const val TAG = "QSFragmentDisableFlagsLog"
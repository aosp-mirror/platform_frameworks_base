package com.android.systemui.qs

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.QSDisableLog
import com.android.systemui.statusbar.disableflags.DisableFlagsLogger
import javax.inject.Inject

/** A helper class for logging disable flag changes made in [QSImpl]. */
class QSDisableFlagsLogger
@Inject
constructor(
    @QSDisableLog private val buffer: LogBuffer,
    private val disableFlagsLogger: DisableFlagsLogger
) {

    /**
     * Logs a string representing the new state received by [QSImpl] and any modifications that were
     * made to the flags locally.
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
                    new = DisableFlagsLogger.DisableState(int1, int2),
                    newAfterLocalModification =
                        DisableFlagsLogger.DisableState(long1.toInt(), long2.toInt())
                )
            }
        )
    }
}

private const val TAG = "QSDisableFlagsLog"

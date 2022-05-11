package com.android.systemui.statusbar.notification.collection.coordinator

import android.util.Log
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.NotificationHeadsUpLog
import javax.inject.Inject

private const val TAG = "HeadsUpCoordinator"

class HeadsUpCoordinatorLogger constructor(
    private val buffer: LogBuffer,
    private val verbose: Boolean,
) {
    @Inject
    constructor(@NotificationHeadsUpLog buffer: LogBuffer) :
            this(buffer, Log.isLoggable(TAG, Log.VERBOSE))

    fun logPostedEntryWillEvaluate(posted: HeadsUpCoordinator.PostedEntry, reason: String) {
        if (!verbose) return
        buffer.log(TAG, LogLevel.VERBOSE, {
            str1 = posted.key
            str2 = reason
            bool1 = posted.shouldHeadsUpEver
            bool2 = posted.shouldHeadsUpAgain
        }, {
            "will evaluate posted entry $str1:" +
                    " reason=$str2 shouldHeadsUpEver=$bool1 shouldHeadsUpAgain=$bool2"
        })
    }

    fun logPostedEntryWillNotEvaluate(posted: HeadsUpCoordinator.PostedEntry, reason: String) {
        if (!verbose) return
        buffer.log(TAG, LogLevel.VERBOSE, {
            str1 = posted.key
            str2 = reason
        }, {
            "will not evaluate posted entry $str1: reason=$str2"
        })
    }

    fun logEvaluatingGroups(numGroups: Int) {
        if (!verbose) return
        buffer.log(TAG, LogLevel.VERBOSE, {
            int1 = numGroups
        }, {
            "evaluating groups for alert transfer: $int1"
        })
    }

    fun logEvaluatingGroup(groupKey: String, numPostedEntries: Int, logicalGroupSize: Int) {
        if (!verbose) return
        buffer.log(TAG, LogLevel.VERBOSE, {
            str1 = groupKey
            int1 = numPostedEntries
            int2 = logicalGroupSize
        }, {
            "evaluating group for alert transfer: $str1" +
                    " numPostedEntries=$int1 logicalGroupSize=$int2"
        })
    }
}

package com.android.systemui.statusbar.notification.collection.coordinator

import android.util.Log

import com.android.systemui.log.dagger.NotificationHeadsUpLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
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

    fun logEntryUpdatedByRanking(key: String, shouldHun: Boolean) {
        buffer.log(TAG, LogLevel.DEBUG, {
            str1 = key
            bool1 = shouldHun
        }, {
            "updating entry via ranking applied: $str1 updated shouldHeadsUp=$bool1"
        })
    }

    fun logEntryUpdatedToFullScreen(key: String) {
        buffer.log(TAG, LogLevel.DEBUG, {
            str1 = key
        }, {
            "updating entry to launch full screen intent: $str1"
        })
    }

    fun logSummaryMarkedInterrupted(summaryKey: String, childKey: String) {
        buffer.log(TAG, LogLevel.DEBUG, {
            str1 = summaryKey
            str2 = childKey
        }, {
            "marked group summary as interrupted: $str1 for alert transfer to child: $str2"
        })
    }
}

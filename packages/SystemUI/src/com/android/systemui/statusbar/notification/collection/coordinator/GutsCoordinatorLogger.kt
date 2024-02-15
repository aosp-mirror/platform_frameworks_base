package com.android.systemui.statusbar.notification.collection.coordinator

import com.android.systemui.log.dagger.NotificationLog
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.notification.row.NotificationGuts
import javax.inject.Inject

private const val TAG = "GutsCoordinator"

class GutsCoordinatorLogger @Inject constructor(
    @NotificationLog private val buffer: LogBuffer
) {

    fun logGutsOpened(key: String, guts: NotificationGuts) {
        buffer.log(TAG, LogLevel.DEBUG, {
            str1 = key
            str2 = guts.gutsContent::class.simpleName
            bool1 = guts.isLeavebehind
        }, {
            "Guts of type $str2 (leave behind: $bool1) opened for class $str1"
        })
    }

    fun logGutsClosed(key: String) {
        buffer.log(TAG, LogLevel.DEBUG, {
            str1 = key
        }, {
            "Guts closed for class $str1"
        })
    }
}

package com.android.systemui.statusbar.notification.interruption

import com.android.systemui.log.dagger.NotificationHeadsUpLog
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel.INFO
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.logKey
import javax.inject.Inject

class HeadsUpViewBinderLogger @Inject constructor(@NotificationHeadsUpLog val buffer: LogBuffer) {
    fun startBindingHun(entry: NotificationEntry) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
        }, {
            "start binding heads up entry $str1 "
        })
    }

    fun currentOngoingBindingAborted(entry: NotificationEntry) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
        }, {
            "aborted potential ongoing heads up entry binding $str1 "
        })
    }

    fun entryBoundSuccessfully(entry: NotificationEntry) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
        }, {
            "heads up entry bound successfully $str1 "
        })
    }

    fun entryUnbound(entry: NotificationEntry) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
        }, {
            "heads up entry unbound successfully $str1 "
        })
    }

    fun entryContentViewMarkedFreeable(entry: NotificationEntry) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
        }, {
            "start unbinding heads up entry $str1 "
        })
    }

    fun entryBindStageParamsNullOnUnbind(entry: NotificationEntry) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
        }, {
            "heads up entry bind stage params null on unbind $str1 "
        })
    }
}

private const val TAG = "HeadsUpViewBinder"

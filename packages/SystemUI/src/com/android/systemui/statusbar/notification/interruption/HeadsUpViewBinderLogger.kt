package com.android.systemui.statusbar.notification.interruption

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel.INFO
import com.android.systemui.log.dagger.NotificationHeadsUpLog
import javax.inject.Inject

class HeadsUpViewBinderLogger @Inject constructor(@NotificationHeadsUpLog val buffer: LogBuffer) {
    fun startBindingHun(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "start binding heads up entry $str1 "
        })
    }

    fun currentOngoingBindingAborted(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "aborted potential ongoing heads up entry binding $str1 "
        })
    }

    fun entryBoundSuccessfully(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "heads up entry bound successfully $str1 "
        })
    }

    fun entryUnbound(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "heads up entry unbound successfully $str1 "
        })
    }

    fun entryContentViewMarkedFreeable(key: String) {
        buffer.log(TAG, INFO, {
            str1 = key
        }, {
            "start unbinding heads up entry $str1 "
        })
    }
}

private const val TAG = "HeadsUpViewBinder"

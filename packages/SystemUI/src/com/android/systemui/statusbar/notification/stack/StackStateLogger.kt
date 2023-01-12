package com.android.systemui.statusbar.notification.stack

import com.android.systemui.log.dagger.NotificationHeadsUpLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import com.android.systemui.statusbar.notification.logKey
import javax.inject.Inject

class StackStateLogger @Inject constructor(
    @NotificationHeadsUpLog private val buffer: LogBuffer
) {
    fun logHUNViewDisappearing(key: String) {
        buffer.log(TAG, LogLevel.INFO, {
            str1 = logKey(key)
        }, {
            "Heads up view disappearing $str1 "
        })
    }

    fun logHUNViewAppearing(key: String) {
        buffer.log(TAG, LogLevel.INFO, {
            str1 = logKey(key)
        }, {
            "Heads up notification view appearing $str1 "
        })
    }

    fun logHUNViewDisappearingWithRemoveEvent(key: String) {
        buffer.log(TAG, LogLevel.ERROR, {
            str1 = logKey(key)
        }, {
            "Heads up view disappearing $str1 for ANIMATION_TYPE_REMOVE"
        })
    }

    fun logHUNViewAppearingWithAddEvent(key: String) {
        buffer.log(TAG, LogLevel.ERROR, {
            str1 = logKey(key)
        }, {
            "Heads up view disappearing $str1 for ANIMATION_TYPE_ADD"
        })
    }

    fun disappearAnimationEnded(key: String) {
        buffer.log(TAG, LogLevel.INFO, {
            str1 = logKey(key)
        }, {
            "Heads up notification disappear animation ended $str1 "
        })
    }

    fun appearAnimationEnded(key: String) {
        buffer.log(TAG, LogLevel.INFO, {
            str1 = logKey(key)
        }, {
            "Heads up notification appear animation ended $str1 "
        })
    }
}

private const val TAG = "StackScroll"
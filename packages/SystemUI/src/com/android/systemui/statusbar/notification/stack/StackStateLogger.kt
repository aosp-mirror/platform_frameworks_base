package com.android.systemui.statusbar.notification.stack

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.NotificationHeadsUpLog
import com.android.systemui.log.dagger.NotificationRenderLog
import com.android.systemui.statusbar.notification.logKey
import com.android.systemui.util.visibilityString
import javax.inject.Inject

class StackStateLogger
@Inject
constructor(
    @NotificationHeadsUpLog private val buffer: LogBuffer,
    @NotificationRenderLog private val notificationRenderBuffer: LogBuffer
) {
    fun logHUNViewDisappearing(key: String) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = logKey(key) },
            { "Heads up view disappearing $str1 " }
        )
    }

    fun logHUNViewAppearing(key: String) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = logKey(key) },
            { "Heads up notification view appearing $str1 " }
        )
    }

    fun logHUNViewDisappearingWithRemoveEvent(key: String) {
        buffer.log(
            TAG,
            LogLevel.ERROR,
            { str1 = logKey(key) },
            { "Heads up view disappearing $str1 for ANIMATION_TYPE_REMOVE" }
        )
    }

    fun logHUNViewAppearingWithAddEvent(key: String) {
        buffer.log(
            TAG,
            LogLevel.ERROR,
            { str1 = logKey(key) },
            { "Heads up view disappearing $str1 for ANIMATION_TYPE_ADD" }
        )
    }

    fun disappearAnimationEnded(key: String) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = logKey(key) },
            { "Heads up notification disappear animation ended $str1 " }
        )
    }

    fun appearAnimationEnded(key: String) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = logKey(key) },
            { "Heads up notification appear animation ended $str1 " }
        )
    }

    fun groupChildRemovalEventProcessed(key: String) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = logKey(key) },
            { "Group Child Notification removal event processed $str1 for ANIMATION_TYPE_REMOVE" }
        )
    }
    fun groupChildRemovalAnimationEnded(key: String) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = logKey(key) },
            { "Group child notification removal animation ended $str1 " }
        )
    }

    fun processAnimationEventsRemoval(key: String, visibility: Int, isHeadsUp: Boolean) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.INFO,
            {
                str1 = logKey(key)
                int1 = visibility
                bool1 = isHeadsUp
            },
            {
                "ProcessAnimationEvents ANIMATION_TYPE_REMOVE for: $str1, " +
                    "changingViewVisibility: ${visibilityString(int1)}, isHeadsUp: $bool1"
            }
        )
    }

    fun processAnimationEventsRemoveSwipeOut(
        key: String,
        isFullySwipedOut: Boolean,
        isHeadsUp: Boolean
    ) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.INFO,
            {
                str1 = logKey(key)
                bool1 = isFullySwipedOut
                bool2 = isHeadsUp
            },
            {
                "ProcessAnimationEvents ANIMATION_TYPE_REMOVE_SWIPED_OUT for: $str1, " +
                    "isFullySwipedOut: $bool1, isHeadsUp: $bool2"
            }
        )
    }

    fun animationStart(key: String?, animationType: String, isHeadsUp: Boolean) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.INFO,
            {
                str1 = logKey(key)
                str2 = animationType
                bool1 = isHeadsUp
            },
            { "Animation Start, type: $str2, notif key: $str1, isHeadsUp: $bool1" }
        )
    }

    fun animationEnd(key: String, animationType: String, isHeadsUp: Boolean) {
        notificationRenderBuffer.log(
            TAG,
            LogLevel.INFO,
            {
                str1 = logKey(key)
                str2 = animationType
                bool1 = isHeadsUp
            },
            { "Animation End, type: $str2, notif key: $str1, isHeadsUp: $bool1" }
        )
    }
}

private const val TAG = "StackScroll"

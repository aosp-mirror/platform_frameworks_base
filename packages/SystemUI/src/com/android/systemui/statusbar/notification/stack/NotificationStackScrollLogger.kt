package com.android.systemui.statusbar.notification.stack

import android.view.ViewGroup
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.core.LogLevel.INFO
import com.android.systemui.log.core.LogLevel.ERROR
import com.android.systemui.log.dagger.NotificationHeadsUpLog
import com.android.systemui.log.dagger.NotificationRenderLog
import com.android.systemui.log.dagger.ShadeLog
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.logKey
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_ADD
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_HEADS_UP_APPEAR
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_HEADS_UP_OTHER
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

class NotificationStackScrollLogger @Inject constructor(
    @NotificationHeadsUpLog private val buffer: LogBuffer,
    @NotificationRenderLog private val notificationRenderBuffer: LogBuffer,
    @ShadeLog private val shadeLogBuffer: LogBuffer,
) {
    fun hunAnimationSkipped(entry: NotificationEntry, reason: String) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
            str2 = reason
        }, {
            "heads up animation skipped: key: $str1 reason: $str2"
        })
    }
    fun hunAnimationEventAdded(entry: NotificationEntry, type: Int) {
        val reason: String
        reason = if (type == ANIMATION_TYPE_HEADS_UP_DISAPPEAR) {
            "HEADS_UP_DISAPPEAR"
        } else if (type == ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK) {
            "HEADS_UP_DISAPPEAR_CLICK"
        } else if (type == ANIMATION_TYPE_HEADS_UP_APPEAR) {
            "HEADS_UP_APPEAR"
        } else if (type == ANIMATION_TYPE_HEADS_UP_OTHER) {
            "HEADS_UP_OTHER"
        } else if (type == ANIMATION_TYPE_ADD) {
            "ADD"
        } else {
            type.toString()
        }
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
            str2 = reason
        }, {
            "heads up animation added: $str1 with type $str2"
        })
    }

    fun hunSkippedForUnexpectedState(entry: NotificationEntry, expected: Boolean, actual: Boolean) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
            bool1 = expected
            bool2 = actual
        }, {
            "HUN animation skipped for unexpected hun state: " +
                    "key: $str1 expected: $bool1 actual: $bool2"
        })
    }

    fun logShadeDebugEvent(@CompileTimeConstant msg: String) = shadeLogBuffer.log(TAG, DEBUG, msg)

    fun logEmptySpaceClick(
        isBelowLastNotification: Boolean,
        statusBarState: Int,
        touchIsClick: Boolean,
        motionEventDesc: String
    ) {
        shadeLogBuffer.log(TAG, DEBUG, {
            int1 = statusBarState
            bool1 = touchIsClick
            bool2 = isBelowLastNotification
            str1 = motionEventDesc
        }, {
            "handleEmptySpaceClick: statusBarState: $int1 isTouchAClick: $bool1 " +
                    "isTouchBelowNotification: $bool2 motionEvent: $str1"
        })
    }

    fun transientNotificationRowTraversalCleaned(entry: NotificationEntry, reason: String) {
        notificationRenderBuffer.log(TAG, INFO, {
            str1 = entry.logKey
            str2 = reason
        }, {
            "transientNotificationRowTraversalCleaned: key: $str1 reason: $str2"
        })
    }

    fun addTransientChildNotificationToChildContainer(
            childEntry: NotificationEntry,
            containerEntry: NotificationEntry,
    ) {
        notificationRenderBuffer.log(TAG, INFO, {
            str1 = childEntry.logKey
            str2 = containerEntry.logKey
        }, {
            "addTransientChildToContainer from onViewRemovedInternal: childKey: $str1 " +
                    "-- containerKey: $str2"
        })
    }

    fun addTransientChildNotificationToNssl(
            childEntry: NotificationEntry,
    ) {
        notificationRenderBuffer.log(TAG, INFO, {
            str1 = childEntry.logKey
        }, {
            "addTransientRowToNssl from onViewRemovedInternal: childKey: $str1"
        })
    }

    fun addTransientChildNotificationToViewGroup(
            childEntry: NotificationEntry,
            container: ViewGroup
    ) {
        notificationRenderBuffer.log(TAG, ERROR, {
            str1 = childEntry.logKey
            str2 = container.toString()
        }, {
            "addTransientRowTo unhandled ViewGroup from onViewRemovedInternal: childKey: $str1 " +
                    "-- ViewGroup: $str2"
        })
    }

    fun addTransientRow(
            childEntry: NotificationEntry,
            index: Int
    ) {
        notificationRenderBuffer.log(
                TAG,
                INFO,
                {
                    str1 = childEntry.logKey
                    int1 = index
                },
                { "addTransientRow to NSSL: childKey: $str1 -- index: $int1" }
        )
    }

    fun removeTransientRow(
            childEntry: NotificationEntry,
    ) {
        notificationRenderBuffer.log(
                TAG,
                INFO,
                {
                    str1 = childEntry.logKey
                },
                { "removeTransientRow from NSSL: childKey: $str1" }
        )
    }
}

private const val TAG = "NotificationStackScroll"
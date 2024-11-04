package com.android.systemui.statusbar.events

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState
import javax.inject.Inject

/** Logs for the SystemStatusAnimationScheduler. */
@SysUISingleton
class SystemStatusAnimationSchedulerLogger
@Inject
constructor(@SystemStatusAnimationSchedulerLog private val logBuffer: LogBuffer) {

    fun logScheduleEvent(event: StatusEvent) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = event.javaClass.simpleName
                int1 = event.priority
                bool1 = event.forceVisible
                bool2 = event.showAnimation
            },
            { "Scheduling event: $str1(forceVisible=$bool1, priority=$int1, showAnimation=$bool2)" },
        )
    }

    fun logUpdateEvent(event: StatusEvent, animationState: SystemEventAnimationState) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = event.javaClass.simpleName
                int1 = event.priority
                bool1 = event.forceVisible
                bool2 = event.showAnimation
                str2 = animationState.name
            },
            {
                "Updating current event from: $str1(forceVisible=$bool1, priority=$int1, " +
                    "showAnimation=$bool2), animationState=$str2"
            },
        )
    }

    fun logIgnoreEvent(event: StatusEvent) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = event.javaClass.simpleName
                int1 = event.priority
                bool1 = event.forceVisible
                bool2 = event.showAnimation
            },
            { "Ignore event: $str1(forceVisible=$bool1, priority=$int1, showAnimation=$bool2)" },
        )
    }

    fun logHidePersistentDotCallbackInvoked() {
        logBuffer.log(TAG, LogLevel.DEBUG, "Hide persistent dot callback invoked")
    }

    fun logTransitionToPersistentDotCallbackInvoked() {
        logBuffer.log(TAG, LogLevel.DEBUG, "Transition to persistent dot callback invoked")
    }

    fun logAnimationStateUpdate(animationState: SystemEventAnimationState) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = animationState.name },
            { "AnimationState update: $str1" },
        )
    }
}

private const val TAG = "SystemStatusAnimationSchedulerLog"

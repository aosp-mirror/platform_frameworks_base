package com.android.systemui.statusbar.events

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import javax.inject.Inject

/** Logs for the SystemStatusAnimationScheduler. */
@SysUISingleton
class SystemStatusAnimationSchedulerLogger
@Inject
constructor(
    @SystemStatusAnimationSchedulerLog private val logBuffer: LogBuffer,
) {

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
            { "Scheduling event: $str1(forceVisible=$bool1, priority=$int1, showAnimation=$bool2)" }
        )
    }

    fun logUpdateEvent(event: StatusEvent, @SystemAnimationState animationState: Int) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = event.javaClass.simpleName
                int1 = event.priority
                bool1 = event.forceVisible
                bool2 = event.showAnimation
                int2 = animationState
            },
            {
                "Updating current event from: $str1(forceVisible=$bool1, priority=$int1, " +
                    "showAnimation=$bool2), animationState=${animationState.name()}"
            }
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
            { "Ignore event: $str1(forceVisible=$bool1, priority=$int1, showAnimation=$bool2)" }
        )
    }

    fun logHidePersistentDotCallbackInvoked() {
        logBuffer.log(TAG, LogLevel.DEBUG, "Hide persistent dot callback invoked")
    }

    fun logTransitionToPersistentDotCallbackInvoked() {
        logBuffer.log(TAG, LogLevel.DEBUG, "Transition to persistent dot callback invoked")
    }

    fun logAnimationStateUpdate(@SystemAnimationState animationState: Int) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { int1 = animationState },
            { "AnimationState update: ${int1.name()}" }
        )
        animationState.name()
    }

    private fun @receiver:SystemAnimationState Int.name() =
        when (this) {
            IDLE -> "IDLE"
            ANIMATION_QUEUED -> "ANIMATION_QUEUED"
            ANIMATING_IN -> "ANIMATING_IN"
            RUNNING_CHIP_ANIM -> "RUNNING_CHIP_ANIM"
            ANIMATING_OUT -> "ANIMATING_OUT"
            SHOWING_PERSISTENT_DOT -> "SHOWING_PERSISTENT_DOT"
            else -> "UNKNOWN_ANIMATION_STATE"
        }
}

private const val TAG = "SystemStatusAnimationSchedulerLog"

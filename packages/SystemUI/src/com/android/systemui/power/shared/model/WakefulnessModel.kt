package com.android.systemui.power.shared.model

import com.android.systemui.keyguard.KeyguardService

/**
 * Models whether the device is awake or asleep, along with information about why we're in that
 * state.
 */
data class WakefulnessModel(
    /**
     * Internal-only wakefulness state, which we receive via [KeyguardService]. This is a more
     * granular state that tells us whether we've started or finished waking up or going to sleep.
     *
     * This distinction has historically been confusing - the display is on once we've "finished"
     * waking up, but we're still playing screen-on animations. Similarly, the screen off animation
     * is still playing even once we've "finished" going to sleep.
     *
     * Avoid using this whenever possible - [isAwake] and [isAsleep] should be sufficient for nearly
     * all use cases. If you need more granular information about a waking/sleeping transition, use
     * the [KeyguardTransitionInteractor].
     */
    val internalWakefulnessState: WakefulnessState = WakefulnessState.AWAKE,
    val lastWakeReason: WakeSleepReason = WakeSleepReason.OTHER,
    val lastSleepReason: WakeSleepReason = WakeSleepReason.OTHER,

    /**
     * Whether the power button double tap gesture was triggered since the last time went to sleep.
     * If this value is true while [isAsleep]=true, it means we'll be waking back up shortly. If it
     * is true while [isAwake]=true, it means we're awake because of the button gesture.
     *
     * This value remains true until the next time [isAsleep]=true, since it would otherwise be
     * totally arbitrary at what point we decide the gesture was no longer "triggered". Since a
     * sleep event is guaranteed to arrive prior to the next power button gesture (as the first tap
     * of the double tap always begins a sleep transition), this will always be reset to false prior
     * to a subsequent power gesture.
     */
    val powerButtonLaunchGestureTriggered: Boolean = false,
) {
    fun isAwake() =
        internalWakefulnessState == WakefulnessState.AWAKE ||
            internalWakefulnessState == WakefulnessState.STARTING_TO_WAKE

    fun isAsleep() = !isAwake()

    fun isAwakeFrom(wakeSleepReason: WakeSleepReason) =
        isAwake() && lastWakeReason == wakeSleepReason

    fun isAwakeFromTouch(): Boolean {
        return isAwake() && lastWakeReason.isTouch
    }

    fun isAsleepFrom(wakeSleepReason: WakeSleepReason) =
        isAsleep() && lastSleepReason == wakeSleepReason

    fun isAwakeOrAsleepFrom(reason: WakeSleepReason) = isAsleepFrom(reason) || isAwakeFrom(reason)

    fun isAwakeFromTapOrGesture(): Boolean {
        return isAwake() &&
            (lastWakeReason == WakeSleepReason.TAP || lastWakeReason == WakeSleepReason.GESTURE)
    }
}

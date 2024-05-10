/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.systemui.power.domain.interactor

import android.os.PowerManager
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.FalsingCollectorActual
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.data.repository.PowerRepository
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Hosts business logic for interacting with the power system. */
@SysUISingleton
class PowerInteractor
@Inject
constructor(
    private val repository: PowerRepository,
    @FalsingCollectorActual private val falsingCollector: FalsingCollector,
    private val screenOffAnimationController: ScreenOffAnimationController,
    private val statusBarStateController: StatusBarStateController,
) {
    /** Whether the screen is on or off. */
    val isInteractive: Flow<Boolean> = repository.isInteractive

    /**
     * Whether we're awake or asleep, along with additional information about why we're awake/asleep
     * and whether the power button gesture has been triggered (a special case that affects
     * wakefulness).
     *
     * Unless you need to respond differently to different [WakeSleepReason]s, you should use
     * [isAwake].
     */
    val detailedWakefulness = repository.wakefulness

    /**
     * Whether we're awake (screen is on and responding to user touch) or asleep (screen is off, or
     * on AOD).
     */
    val isAwake =
        repository.wakefulness
            .map { it.isAwake() }
            .distinctUntilChanged(checkEquivalentUnlessEmitDuplicatesUnderTest)

    /** Helper flow in case "isAsleep" reads better than "!isAwake". */
    val isAsleep = isAwake.map { !it }

    val screenPowerState = repository.screenPowerState

    /**
     * Notifies the power interactor that a user touch happened.
     *
     * @param noChangeLights If true, does not cause the keyboard backlight to turn on because of
     *   this event. This is set when the power key is pressed. We want the device to stay on while
     *   the button is down, but we're about to turn off the screen so we don't want the keyboard
     *   backlight to turn on again. Otherwise the lights flash on and then off and it looks weird.
     */
    fun onUserTouch(noChangeLights: Boolean = false) =
        repository.userTouch(noChangeLights = noChangeLights)

    /**
     * Wakes up the device if the device was dozing.
     *
     * @param why a string explaining why we're waking the device for debugging purposes. Should be
     *   in SCREAMING_SNAKE_CASE.
     * @param wakeReason the PowerManager-based reason why we're waking the device.
     */
    fun wakeUpIfDozing(why: String, @PowerManager.WakeReason wakeReason: Int) {
        if (
            statusBarStateController.isDozing && screenOffAnimationController.allowWakeUpIfDozing()
        ) {
            repository.wakeUp(why, wakeReason)
            falsingCollector.onScreenOnFromTouch()
        }
    }

    /**
     * Wakes up the device if the device was dozing or going to sleep in order to display a
     * full-screen intent.
     */
    fun wakeUpForFullScreenIntent() {
        if (repository.wakefulness.value.isAsleep() || statusBarStateController.isDozing) {
            repository.wakeUp(why = FSI_WAKE_WHY, wakeReason = PowerManager.WAKE_REASON_APPLICATION)
        }
    }

    /**
     * Wakes up the device if dreaming with a screensaver.
     *
     * @param why a string explaining why we're waking the device for debugging purposes. Should be
     *   in SCREAMING_SNAKE_CASE.
     * @param wakeReason the PowerManager-based reason why we're waking the device.
     */
    fun wakeUpIfDreaming(why: String, @PowerManager.WakeReason wakeReason: Int) {
        if (statusBarStateController.isDreaming) {
            repository.wakeUp(why, wakeReason)
        }
    }

    /**
     * Called from [KeyguardService] to inform us that the device has started waking up. This is the
     * canonical source of wakefulness information for System UI. This method should not be called
     * from anywhere else.
     *
     * In tests, you should be able to use [setAwakeForTest] rather than calling this method
     * directly.
     */
    fun onStartedWakingUp(
        @PowerManager.WakeReason reason: Int,
        powerButtonLaunchGestureTriggeredOnWakeUp: Boolean,
    ) {
        // If the launch gesture was previously detected, either via onCameraLaunchGestureDetected
        // or onFinishedGoingToSleep(), carry that state forward. It will be reset by the next
        // onStartedGoingToSleep.
        val powerButtonLaunchGestureTriggered =
            powerButtonLaunchGestureTriggeredOnWakeUp ||
                repository.wakefulness.value.powerButtonLaunchGestureTriggered

        repository.updateWakefulness(
            rawState = WakefulnessState.STARTING_TO_WAKE,
            lastWakeReason = WakeSleepReason.fromPowerManagerWakeReason(reason),
            powerButtonLaunchGestureTriggered = powerButtonLaunchGestureTriggered,
        )
    }

    /**
     * Called from [KeyguardService] to inform us that the device has finished waking up. This is
     * the canonical source of wakefulness information for System UI. This method should not be
     * called from anywhere else.
     *
     * In tests, you should be able to use [setAwakeForTest] rather than calling this method
     * directly.
     */
    fun onFinishedWakingUp() {
        repository.updateWakefulness(rawState = WakefulnessState.AWAKE)
    }

    /**
     * Called from [KeyguardService] to inform us that the device is going to sleep. This is the
     * canonical source of wakefulness information for System UI. This method should not be called
     * from anywhere else.
     *
     * In tests, you should be able to use [setAsleepForTest] rather than calling this method
     * directly.
     */
    fun onStartedGoingToSleep(@PowerManager.GoToSleepReason reason: Int) {
        repository.updateWakefulness(
            rawState = WakefulnessState.STARTING_TO_SLEEP,
            lastSleepReason = WakeSleepReason.fromPowerManagerSleepReason(reason),
            powerButtonLaunchGestureTriggered = false,
        )
    }

    /**
     * Called from [KeyguardService] to inform us that the device has gone to sleep. This is the
     * canonical source of wakefulness information for System UI. This method should not be called
     * from anywhere else.
     *
     * In tests, you should be able to use [setAsleepForTest] rather than calling this method
     * directly.
     */
    fun onFinishedGoingToSleep(
        powerButtonLaunchGestureTriggeredDuringSleep: Boolean,
    ) {
        // If the launch gesture was previously detected via onCameraLaunchGestureDetected, carry
        // that state forward. It will be reset by the next onStartedGoingToSleep.
        val powerButtonLaunchGestureTriggered =
            powerButtonLaunchGestureTriggeredDuringSleep ||
                repository.wakefulness.value.powerButtonLaunchGestureTriggered

        repository.updateWakefulness(
            rawState = WakefulnessState.ASLEEP,
            powerButtonLaunchGestureTriggered = powerButtonLaunchGestureTriggered,
        )
    }

    fun onScreenPowerStateUpdated(state: ScreenPowerState) {
        repository.setScreenPowerState(state)
    }

    fun onCameraLaunchGestureDetected() {
        repository.updateWakefulness(powerButtonLaunchGestureTriggered = true)
    }

    companion object {
        private const val FSI_WAKE_WHY = "full_screen_intent"

        /**
         * If true, [isAwake] and [isAsleep] will emit the next value even if it's not distinct.
         * This is useful for setting up tests.
         */
        private var emitDuplicateWakefulnessValue = false

        /**
         * Returns whether old == new unless we want to emit duplicate values, in which case we
         * reset that flag and then return false.
         */
        private val checkEquivalentUnlessEmitDuplicatesUnderTest: (Boolean, Boolean) -> Boolean =
            { old, new ->
                if (emitDuplicateWakefulnessValue) {
                    emitDuplicateWakefulnessValue = false
                    false
                } else {
                    old == new
                }
            }

        /**
         * Helper method for tests to simulate the device waking up.
         *
         * If [forceEmit] is true, forces [isAwake] to emit true, even if the PowerInteractor in the
         * test was already awake. This is useful for the first setAwakeForTest call in a test,
         * since otherwise, tests would need to set the PowerInteractor asleep first to ensure
         * [isAwake] emits, which can cause superfluous interactions with mocks.
         *
         * This is also preferred to calling [onStartedWakingUp]/[onFinishedWakingUp] directly, as
         * we want to keep the started/finished concepts internal to keyguard as much as possible.
         */
        @JvmOverloads
        fun PowerInteractor.setAwakeForTest(
            @PowerManager.WakeReason reason: Int = PowerManager.WAKE_REASON_UNKNOWN,
            forceEmit: Boolean = false
        ) {
            emitDuplicateWakefulnessValue = forceEmit

            this.onStartedWakingUp(
                reason = reason,
                powerButtonLaunchGestureTriggeredOnWakeUp = false,
            )
            this.onFinishedWakingUp()
        }

        /**
         * Helper method for tests to simulate the device sleeping.
         *
         * If [forceEmit] is true, forces [isAsleep] to emit true, even if the PowerInteractor in
         * the test was already asleep. This is useful for the first setAsleepForTest call in a
         * test, since otherwise, tests would need to set the PowerInteractor awake first to ensure
         * [isAsleep] emits, but that can cause superfluous interactions with mocks.
         *
         * This is also preferred to calling [onStartedGoingToSleep]/[onFinishedGoingToSleep]
         * directly, as we want to keep the started/finished concepts internal to keyguard as much
         * as possible.
         */
        @JvmOverloads
        fun PowerInteractor.setAsleepForTest(
            @PowerManager.GoToSleepReason sleepReason: Int = PowerManager.GO_TO_SLEEP_REASON_MIN,
            forceEmit: Boolean = false,
        ) {
            emitDuplicateWakefulnessValue = forceEmit

            this.onStartedGoingToSleep(reason = sleepReason)
            this.onFinishedGoingToSleep(
                powerButtonLaunchGestureTriggeredDuringSleep = false,
            )
        }
    }
}

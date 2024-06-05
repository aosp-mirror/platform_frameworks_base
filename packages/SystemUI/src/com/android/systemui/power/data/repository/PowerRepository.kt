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

package com.android.systemui.power.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessModel
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Defines interface for classes that act as source of truth for power-related data. */
interface PowerRepository {
    /** Whether the device is interactive. Starts with the current state. */
    val isInteractive: Flow<Boolean>

    /**
     * Whether the device is awake or asleep. [WakefulnessState.AWAKE] means the screen is fully
     * powered on, and the user can interact with the device. [WakefulnessState.ASLEEP] means the
     * screen is either off, or in low-power always-on-display mode - in either case, the user
     * cannot interact with the device and will need to wake it up somehow if they wish to do so.
     */
    val wakefulness: StateFlow<WakefulnessModel>

    /**
     * The physical on/off state of the display. [ScreenPowerState.SCREEN_OFF] means the display is
     * unpowered and nothing is visible. [ScreenPowerState.SCREEN_ON] means the display is either
     * fully powered on, or it's in low-power always-on-display (AOD) mode showing the time and
     * other info.
     *
     * YOU PROBABLY DO NOT WANT TO USE THIS STATE. Almost all System UI use cases for screen state
     * expect that the screen would be considered "off" if we're on AOD, which is not the case for
     * [screenPowerState]. Consider [wakefulness] instead.
     */
    val screenPowerState: StateFlow<ScreenPowerState>

    /** Wakes up the device. */
    fun wakeUp(why: String, @PowerManager.WakeReason wakeReason: Int)

    /**
     * Notifies the power repository that a user touch happened.
     *
     * @param noChangeLights If true, does not cause the keyboard backlight to turn on because of
     *   this event. This is set when the power key is pressed. We want the device to stay on while
     *   the button is down, but we're about to turn off the screen so we don't want the keyboard
     *   backlight to turn on again. Otherwise the lights flash on and then off and it looks weird.
     */
    fun userTouch(noChangeLights: Boolean = false)

    /** Updates the wakefulness state, keeping previous values by default. */
    fun updateWakefulness(
        rawState: WakefulnessState = wakefulness.value.internalWakefulnessState,
        lastWakeReason: WakeSleepReason = wakefulness.value.lastWakeReason,
        lastSleepReason: WakeSleepReason = wakefulness.value.lastSleepReason,
        powerButtonLaunchGestureTriggered: Boolean =
            wakefulness.value.powerButtonLaunchGestureTriggered,
    )

    /** Updates the screen power state. */
    fun setScreenPowerState(state: ScreenPowerState)
}

@SysUISingleton
class PowerRepositoryImpl
@Inject
constructor(
    private val manager: PowerManager,
    @Application private val applicationContext: Context,
    private val systemClock: SystemClock,
    dispatcher: BroadcastDispatcher,
) : PowerRepository {

    override val isInteractive: Flow<Boolean> = conflatedCallbackFlow {
        fun send() {
            trySendWithFailureLogging(manager.isInteractive, TAG)
        }

        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    send()
                }
            }

        dispatcher.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        send()

        awaitClose { dispatcher.unregisterReceiver(receiver) }
    }

    private val _wakefulness = MutableStateFlow(WakefulnessModel())
    override val wakefulness = _wakefulness.asStateFlow()

    override fun updateWakefulness(
        rawState: WakefulnessState,
        lastWakeReason: WakeSleepReason,
        lastSleepReason: WakeSleepReason,
        powerButtonLaunchGestureTriggered: Boolean,
    ) {
        _wakefulness.value =
            WakefulnessModel(
                rawState,
                lastWakeReason,
                lastSleepReason,
                powerButtonLaunchGestureTriggered,
            )
    }

    private val _screenPowerState = MutableStateFlow(ScreenPowerState.SCREEN_OFF)
    override val screenPowerState = _screenPowerState.asStateFlow()

    override fun setScreenPowerState(state: ScreenPowerState) {
        _screenPowerState.value = state
    }

    override fun wakeUp(why: String, wakeReason: Int) {
        manager.wakeUp(
            systemClock.uptimeMillis(),
            wakeReason,
            "${applicationContext.packageName}:$why",
        )
    }

    override fun userTouch(noChangeLights: Boolean) {
        manager.userActivity(
            systemClock.uptimeMillis(),
            PowerManager.USER_ACTIVITY_EVENT_TOUCH,
            if (noChangeLights) PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS else 0,
        )
    }

    companion object {
        private const val TAG = "PowerRepository"
    }
}

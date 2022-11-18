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
 */

package com.android.systemui.flags

import android.util.Log
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_ASLEEP
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Restarts SystemUI when the device appears idle. */
class FeatureFlagsReleaseRestarter
@Inject
constructor(
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val batteryController: BatteryController,
    @Background private val bgExecutor: DelayableExecutor,
    private val systemExitRestarter: SystemExitRestarter
) : Restarter {
    var listenersAdded = false
    var pendingRestart: Runnable? = null
    var androidRestartRequested = false

    val observer =
        object : WakefulnessLifecycle.Observer {
            override fun onFinishedGoingToSleep() {
                scheduleRestart()
            }
        }

    val batteryCallback =
        object : BatteryController.BatteryStateChangeCallback {
            override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
                scheduleRestart()
            }
        }

    override fun restartSystemUI() {
        Log.d(
            FeatureFlagsDebug.TAG,
            "SystemUI Restart requested. Restarting when plugged in and idle."
        )
        scheduleRestart()
    }

    override fun restartAndroid() {
        Log.d(
            FeatureFlagsDebug.TAG,
            "Android Restart requested. Restarting when plugged in and idle."
        )
        androidRestartRequested = true
        scheduleRestart()
    }

    private fun scheduleRestart() {
        // Don't bother adding listeners twice.
        if (!listenersAdded) {
            listenersAdded = true
            wakefulnessLifecycle.addObserver(observer)
            batteryController.addCallback(batteryCallback)
        }
        if (
            wakefulnessLifecycle.wakefulness == WAKEFULNESS_ASLEEP && batteryController.isPluggedIn
        ) {
            if (pendingRestart == null) {
                pendingRestart = bgExecutor.executeDelayed(this::restartNow, 30L, TimeUnit.SECONDS)
            }
        } else if (pendingRestart != null) {
            pendingRestart?.run()
            pendingRestart = null
        }
    }

    private fun restartNow() {
        Log.d(FeatureFlagsRelease.TAG, "Restarting due to systemui flag change")
        if (androidRestartRequested) {
            systemExitRestarter.restartAndroid()
        } else {
            systemExitRestarter.restartSystemUI()
        }
    }
}

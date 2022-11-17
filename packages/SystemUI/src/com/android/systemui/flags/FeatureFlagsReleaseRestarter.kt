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
    var shouldRestart = false
    var pendingRestart: Runnable? = null

    val observer =
        object : WakefulnessLifecycle.Observer {
            override fun onFinishedGoingToSleep() {
                maybeScheduleRestart()
            }
        }

    val batteryCallback =
        object : BatteryController.BatteryStateChangeCallback {
            override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
                maybeScheduleRestart()
            }
        }

    override fun restart() {
        Log.d(FeatureFlagsDebug.TAG, "Restart requested. Restarting when plugged in and idle.")
        if (!shouldRestart) {
            // Don't bother scheduling twice.
            shouldRestart = true
            wakefulnessLifecycle.addObserver(observer)
            batteryController.addCallback(batteryCallback)
            maybeScheduleRestart()
        }
    }

    private fun maybeScheduleRestart() {
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
        systemExitRestarter.restart()
    }
}

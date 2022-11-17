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
import com.android.systemui.keyguard.WakefulnessLifecycle
import javax.inject.Inject

/** Restarts SystemUI when the screen is locked. */
class FeatureFlagsDebugRestarter
@Inject
constructor(
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val systemExitRestarter: SystemExitRestarter,
) : Restarter {

    val observer =
        object : WakefulnessLifecycle.Observer {
            override fun onFinishedGoingToSleep() {
                Log.d(FeatureFlagsDebug.TAG, "Restarting due to systemui flag change")
                restartNow()
            }
        }

    override fun restart() {
        Log.d(FeatureFlagsDebug.TAG, "Restart requested. Restarting on next screen off.")
        if (wakefulnessLifecycle.wakefulness == WakefulnessLifecycle.WAKEFULNESS_ASLEEP) {
            restartNow()
        } else {
            wakefulnessLifecycle.addObserver(observer)
        }
    }

    private fun restartNow() {
        systemExitRestarter.restart()
    }
}

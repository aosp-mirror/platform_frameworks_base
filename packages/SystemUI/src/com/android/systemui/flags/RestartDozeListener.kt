/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.os.PowerManager
import android.util.Log
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject

@SysUISingleton
class RestartDozeListener
@Inject
constructor(
    private val settings: SecureSettings,
    private val statusBarStateController: StatusBarStateController,
    private val powerManager: PowerManager,
    private val systemClock: SystemClock,
    @Background val bgExecutor: DelayableExecutor,
) {

    companion object {
        @VisibleForTesting val RESTART_SLEEP_KEY = "restart_nap_after_start"
    }

    private var inited = false

    val listener =
        object : StatusBarStateController.StateListener {
            override fun onDozingChanged(isDozing: Boolean) {
                storeSleepState(isDozing)
            }
        }

    fun init() {
        if (inited) {
            return
        }
        inited = true

        statusBarStateController.addCallback(listener)
    }

    fun destroy() {
        statusBarStateController.removeCallback(listener)
    }

    fun maybeRestartSleep() {
        bgExecutor.executeDelayed(
            {
                if (settings.getBool(RESTART_SLEEP_KEY, false)) {
                    Log.d("RestartDozeListener", "Restarting sleep state")
                    powerManager.wakeUp(
                        systemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_APPLICATION,
                        "RestartDozeListener"
                    )
                    powerManager.goToSleep(systemClock.uptimeMillis())
                }
            },
            1000
        )
    }

    private fun storeSleepState(sleeping: Boolean) {
        bgExecutor.execute { settings.putBool(RESTART_SLEEP_KEY, sleeping) }
    }
}

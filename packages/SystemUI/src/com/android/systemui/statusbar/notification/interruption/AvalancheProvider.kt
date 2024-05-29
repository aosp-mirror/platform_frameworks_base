/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.interruption

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.android.internal.logging.UiEventLogger
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

// Class to track avalanche trigger event time.
@SysUISingleton
class AvalancheProvider
@Inject
constructor(
        private val broadcastDispatcher: BroadcastDispatcher,
        private val logger: VisualInterruptionDecisionLogger,
        private val uiEventLogger: UiEventLogger,
) {
    val TAG = "AvalancheProvider"
    val timeoutMs = 120000
    var startTime: Long = 0L

    private val avalancheTriggerIntents = mutableSetOf(
            Intent.ACTION_AIRPLANE_MODE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MANAGED_PROFILE_AVAILABLE,
            Intent.ACTION_USER_SWITCHED
    )

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action in avalancheTriggerIntents) {

                // Ignore when airplane mode turned on
                if (intent.action == Intent.ACTION_AIRPLANE_MODE_CHANGED
                        && intent.getBooleanExtra(/* name= */ "state", /* defaultValue */ false)) {
                    Log.d(TAG, "broadcastReceiver: ignore airplane mode on")
                    return
                }
                Log.d(TAG, "broadcastReceiver received intent.action=" + intent.action)
                uiEventLogger.log(AvalancheSuppressor.AvalancheEvent.START);
                startTime = System.currentTimeMillis()
            }
        }
    }

    fun register() {
        val intentFilter = IntentFilter()
        for (intent in avalancheTriggerIntents) {
            intentFilter.addAction(intent)
        }
        broadcastDispatcher.registerReceiver(broadcastReceiver, intentFilter)
    }
}
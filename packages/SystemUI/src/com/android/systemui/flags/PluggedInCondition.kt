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

import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.statusbar.policy.BatteryController
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose

/** Returns true when the device is plugged in. */
class PluggedInCondition
@Inject
constructor(
    private val batteryControllerLazy: Lazy<BatteryController>,
) : ConditionalRestarter.Condition {

    override val canRestartNow = conflatedCallbackFlow {
        val batteryCallback =
            object : BatteryController.BatteryStateChangeCallback {
                override fun onBatteryLevelChanged(
                    level: Int,
                    pluggedIn: Boolean,
                    charging: Boolean
                ) {
                    trySend(pluggedIn)
                }
            }
        batteryControllerLazy.get().addCallback(batteryCallback)

        trySend(batteryControllerLazy.get().isPluggedIn)

        awaitClose { batteryControllerLazy.get().removeCallback(batteryCallback) }
    }
}

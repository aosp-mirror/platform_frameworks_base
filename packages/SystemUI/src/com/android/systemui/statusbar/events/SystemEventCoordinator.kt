/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.events

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.statusbar.policy.BatteryController
import javax.inject.Inject

/**
 * Listens for system events (battery, privacy, connectivity) and allows listeners
 * to show status bar animations when they happen
 */
@SysUISingleton
class SystemEventCoordinator @Inject constructor(
    private val batteryController: BatteryController,
    private val privacyController: PrivacyItemController
) {
    private lateinit var scheduler: SystemStatusAnimationScheduler

    fun startObserving() {
        /* currently unused
        batteryController.addCallback(batteryStateListener)
        */
        privacyController.addCallback(privacyStateListener)
    }

    fun stopObserving() {
        /* currently unused
        batteryController.removeCallback(batteryStateListener)
        */
        privacyController.removeCallback(privacyStateListener)
    }

    fun attachScheduler(s: SystemStatusAnimationScheduler) {
        this.scheduler = s
    }

    fun notifyPluggedIn() {
        scheduler.onStatusEvent(BatteryEvent())
    }

    fun notifyPrivacyItemsEmpty() {
        scheduler.setShouldShowPersistentPrivacyIndicator(false)
    }

    fun notifyPrivacyItemsChanged() {
        val event = PrivacyEvent()
        event.privacyItems = privacyStateListener.currentPrivacyItems
        scheduler.onStatusEvent(event)
    }

    private val batteryStateListener = object : BatteryController.BatteryStateChangeCallback {
        var plugged = false
        var stateKnown = false
        override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
            if (!stateKnown) {
                stateKnown = true
                plugged = pluggedIn
                notifyListeners()
                return
            }

            if (plugged != pluggedIn) {
                plugged = pluggedIn
                notifyListeners()
            }
        }

        private fun notifyListeners() {
            // We only care about the plugged in status
            if (plugged) notifyPluggedIn()
        }
    }

    private val privacyStateListener = object : PrivacyItemController.Callback {
        var currentPrivacyItems = listOf<PrivacyItem>()

        override fun onPrivacyItemsChanged(privacyItems: List<PrivacyItem>) {
            currentPrivacyItems = privacyItems
            notifyListeners()
        }

        private fun notifyListeners() {
            if (currentPrivacyItems.isEmpty()) {
                notifyPrivacyItemsEmpty()
            } else {
                notifyPrivacyItemsChanged()
            }
        }
    }
}

private const val TAG = "SystemEventCoordinator"
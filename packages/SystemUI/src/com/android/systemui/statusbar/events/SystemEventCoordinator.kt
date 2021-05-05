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

import android.os.SystemClock
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

    fun notifyPrivacyItemsChanged(showAnimation: Boolean = true) {
        val event = PrivacyEvent()
        event.privacyItems = privacyStateListener.currentPrivacyItems
        scheduler.onStatusEvent(event, showAnimation)
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
        var previousPrivacyItems = listOf<PrivacyItem>()
        var timeLastEmpty = SystemClock.elapsedRealtime()

        override fun onPrivacyItemsChanged(privacyItems: List<PrivacyItem>) {
            if (uniqueItemsMatch(privacyItems, currentPrivacyItems)) {
                return
            } else if (privacyItems.isEmpty()) {
                previousPrivacyItems = currentPrivacyItems
                timeLastEmpty = SystemClock.elapsedRealtime()
            }

            currentPrivacyItems = privacyItems
            notifyListeners()
        }

        private fun notifyListeners() {
            if (currentPrivacyItems.isEmpty()) {
                notifyPrivacyItemsEmpty()
            } else {
                val showAnimation = !uniqueItemsMatch(currentPrivacyItems, previousPrivacyItems) ||
                SystemClock.elapsedRealtime() - timeLastEmpty >= DEBOUNCE_TIME
                notifyPrivacyItemsChanged(showAnimation)
            }
        }

        // Return true if the lists contain the same permission groups, used by the same UIDs
        private fun uniqueItemsMatch(one: List<PrivacyItem>, two: List<PrivacyItem>): Boolean {
            return one.map { it.application.uid to it.privacyType.permGroupName }.toSet() ==
                two.map { it.application.uid to it.privacyType.permGroupName }.toSet()
        }
    }
}

private const val DEBOUNCE_TIME = 3000L
private const val TAG = "SystemEventCoordinator"
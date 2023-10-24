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

package com.android.systemui.statusbar.notification.interruption

import android.database.ContentObserver
import android.hardware.display.AmbientDisplayConfiguration
import android.os.Handler
import android.provider.Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED
import android.provider.Settings.Global.HEADS_UP_OFF
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PEEK
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PULSE
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.util.settings.GlobalSettings

class PeekDisabledSuppressor(
    private val globalSettings: GlobalSettings,
    private val headsUpManager: HeadsUpManager,
    private val logger: NotificationInterruptLogger,
    @Main private val mainHandler: Handler,
) : VisualInterruptionCondition(types = setOf(PEEK), reason = "peek setting disabled") {
    private var isEnabled = false

    override fun shouldSuppress(): Boolean = !isEnabled

    override fun start() {
        val observer =
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    val wasEnabled = isEnabled

                    isEnabled =
                        globalSettings.getInt(HEADS_UP_NOTIFICATIONS_ENABLED, HEADS_UP_OFF) !=
                            HEADS_UP_OFF

                    // QQQ: Do we want to log this even if it hasn't changed?
                    logger.logHeadsUpFeatureChanged(isEnabled)

                    // QQQ: Is there a better place for this side effect? What if HeadsUpManager
                    // registered for it directly?
                    if (wasEnabled && !isEnabled) {
                        logger.logWillDismissAll()
                        headsUpManager.releaseAllImmediately()
                    }
                }
            }

        globalSettings.registerContentObserver(
            globalSettings.getUriFor(HEADS_UP_NOTIFICATIONS_ENABLED),
            /* notifyForDescendants = */ true,
            observer
        )

        // QQQ: Do we need to register for SETTING_HEADS_UP_TICKER? It seems unused.

        observer.onChange(/* selfChange = */ true)
    }
}

class PulseDisabledSuppressor(
    private val ambientDisplayConfiguration: AmbientDisplayConfiguration,
    private val userTracker: UserTracker,
) : VisualInterruptionCondition(types = setOf(PULSE), reason = "pulse setting disabled") {
    override fun shouldSuppress(): Boolean =
        !ambientDisplayConfiguration.pulseOnNotificationEnabled(userTracker.userId)
}

class PulseBatterySaverSuppressor(private val batteryController: BatteryController) :
    VisualInterruptionCondition(
        types = setOf(PULSE),
        reason = "pulsing disabled by battery saver"
    ) {
    override fun shouldSuppress() = batteryController.isAodPowerSave()
}

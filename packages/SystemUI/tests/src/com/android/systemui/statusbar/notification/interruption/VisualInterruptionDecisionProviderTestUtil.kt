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

import android.content.pm.PackageManager
import android.hardware.display.AmbientDisplayConfiguration
import android.os.Handler
import android.os.PowerManager
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.notification.NotifPipelineFlags
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.EventLog
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SystemSettings
import com.android.systemui.util.time.SystemClock

object VisualInterruptionDecisionProviderTestUtil {
    fun createProviderByFlag(
        ambientDisplayConfiguration: AmbientDisplayConfiguration,
        batteryController: BatteryController,
        deviceProvisionedController: DeviceProvisionedController,
        eventLog: EventLog,
        flags: NotifPipelineFlags,
        globalSettings: GlobalSettings,
        headsUpManager: HeadsUpManager,
        keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider,
        keyguardStateController: KeyguardStateController,
        @Main mainHandler: Handler,
        newLogger: VisualInterruptionDecisionLogger,
        oldLogger: NotificationInterruptLogger,
        powerManager: PowerManager,
        statusBarStateController: StatusBarStateController,
        systemClock: SystemClock,
        uiEventLogger: UiEventLogger,
        userTracker: UserTracker,
        avalancheProvider: AvalancheProvider,
        systemSettings: SystemSettings,
        packageManager: PackageManager,
    ): VisualInterruptionDecisionProvider {
        return if (VisualInterruptionRefactor.isEnabled) {
            VisualInterruptionDecisionProviderImpl(
                ambientDisplayConfiguration,
                batteryController,
                deviceProvisionedController,
                eventLog,
                globalSettings,
                headsUpManager,
                keyguardNotificationVisibilityProvider,
                keyguardStateController,
                newLogger,
                mainHandler,
                powerManager,
                statusBarStateController,
                systemClock,
                uiEventLogger,
                userTracker,
                avalancheProvider,
                systemSettings,
                packageManager
            )
        } else {
            NotificationInterruptStateProviderWrapper(
                NotificationInterruptStateProviderImpl(
                    powerManager,
                    ambientDisplayConfiguration,
                    batteryController,
                    statusBarStateController,
                    keyguardStateController,
                    headsUpManager,
                    oldLogger,
                    mainHandler,
                    flags,
                    keyguardNotificationVisibilityProvider,
                    uiEventLogger,
                    userTracker,
                    deviceProvisionedController,
                    systemClock,
                    globalSettings,
                    eventLog
                )
            )
        }
    }
}

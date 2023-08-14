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
package com.android

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.os.UserManager
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.GuestResumeSessionReceiver
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.BroadcastDispatcherLog
import com.android.systemui.log.dagger.SceneFrameworkLog
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.SplitShadeStateController
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.mockito.mock
import com.android.wm.shell.bubbles.Bubbles
import dagger.Module
import dagger.Provides
import java.util.Optional

@Module
data class TestMocksModule(
    @get:Provides val activityStarter: ActivityStarter = mock(),
    @get:Provides val bubbles: Optional<Bubbles> = Optional.of(mock()),
    @get:Provides val configurationController: ConfigurationController = mock(),
    @get:Provides val darkIconDispatcher: DarkIconDispatcher = mock(),
    @get:Provides val demoModeController: DemoModeController = mock(),
    @get:Provides val deviceProvisionedController: DeviceProvisionedController = mock(),
    @get:Provides val dozeParameters: DozeParameters = mock(),
    @get:Provides val guestResumeSessionReceiver: GuestResumeSessionReceiver = mock(),
    @get:Provides val keyguardBypassController: KeyguardBypassController = mock(),
    @get:Provides val keyguardSecurityModel: KeyguardSecurityModel = mock(),
    @get:Provides val keyguardUpdateMonitor: KeyguardUpdateMonitor = mock(),
    @get:Provides val notifListener: NotificationListener = mock(),
    @get:Provides val notifMediaManager: NotificationMediaManager = mock(),
    @get:Provides val screenOffAnimController: ScreenOffAnimationController = mock(),
    @get:Provides val splitShadeStateController: SplitShadeStateController = mock(),
    @get:Provides val statusBarStateController: StatusBarStateController = mock(),
    @get:Provides val statusBarWindowController: StatusBarWindowController = mock(),
    @get:Provides val wakeUpCoordinator: NotificationWakeUpCoordinator = mock(),

    // log buffers
    @get:[Provides BroadcastDispatcherLog]
    val broadcastDispatcherLogger: LogBuffer = mock(),
    @get:[Provides SceneFrameworkLog]
    val sceneLogger: LogBuffer = mock(),

    // framework mocks
    @get:Provides val activityManager: ActivityManager = mock(),
    @get:Provides val devicePolicyManager: DevicePolicyManager = mock(),
    @get:Provides val userManager: UserManager = mock(),
)

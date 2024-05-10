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
package com.android.systemui

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.os.UserManager
import android.util.DisplayMetrics
import android.view.LayoutInflater
import com.android.internal.logging.MetricsLogger
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardViewController
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.BiometricLog
import com.android.systemui.log.dagger.BroadcastDispatcherLog
import com.android.systemui.log.dagger.SceneFrameworkLog
import com.android.systemui.media.controls.ui.MediaHierarchyManager
import com.android.systemui.model.SysUiState
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.NotificationShadeDepthController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator
import com.android.systemui.statusbar.notification.collection.NotifCollection
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.LSShadeTransitionLogger
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.util.mockito.mock
import com.android.wm.shell.bubbles.Bubbles
import dagger.Binds
import dagger.Module
import dagger.Provides
import java.util.Optional

@Module(includes = [TestMocksModule.Bindings::class])
data class TestMocksModule(
    @get:Provides val activityStarter: ActivityStarter = mock(),
    @get:Provides val activityManagerWrapper: ActivityManagerWrapper = mock(),
    @get:Provides val ambientState: AmbientState = mock(),
    @get:Provides val bubbles: Optional<Bubbles> = Optional.of(mock()),
    @get:Provides val darkIconDispatcher: DarkIconDispatcher = mock(),
    @get:Provides val demoModeController: DemoModeController = mock(),
    @get:Provides val deviceProvisionedController: DeviceProvisionedController = mock(),
    @get:Provides val dozeParameters: DozeParameters = mock(),
    @get:Provides val dumpManager: DumpManager = mock(),
    @get:Provides val guestResumeSessionReceiver: GuestResumeSessionReceiver = mock(),
    @get:Provides val keyguardBypassController: KeyguardBypassController = mock(),
    @get:Provides val keyguardSecurityModel: KeyguardSecurityModel = mock(),
    @get:Provides val keyguardUpdateMonitor: KeyguardUpdateMonitor = mock(),
    @get:Provides val layoutInflater: LayoutInflater = mock(),
    @get:Provides
    val lockscreenShadeTransitionController: LockscreenShadeTransitionController = mock(),
    @get:Provides val mediaHierarchyManager: MediaHierarchyManager = mock(),
    @get:Provides val notifCollection: NotifCollection = mock(),
    @get:Provides val notificationListener: NotificationListener = mock(),
    @get:Provides val notificationLockscreenUserManager: NotificationLockscreenUserManager = mock(),
    @get:Provides val notificationMediaManager: NotificationMediaManager = mock(),
    @get:Provides val notificationShadeDepthController: NotificationShadeDepthController = mock(),
    @get:Provides
    val notificationStackScrollLayoutController: NotificationStackScrollLayoutController = mock(),
    @get:Provides val notificationStackSizeCalculator: NotificationStackSizeCalculator = mock(),
    @get:Provides val notificationWakeUpCoordinator: NotificationWakeUpCoordinator = mock(),
    @get:Provides val screenLifecycle: ScreenLifecycle = mock(),
    @get:Provides val screenOffAnimationController: ScreenOffAnimationController = mock(),
    @get:Provides val scrimController: ScrimController = mock(),
    @get:Provides val statusBarStateController: SysuiStatusBarStateController = mock(),
    @get:Provides val statusBarWindowController: StatusBarWindowController = mock(),
    @get:Provides val wakefulnessLifecycle: WakefulnessLifecycle = mock(),
    @get:Provides val keyguardViewController: KeyguardViewController = mock(),
    @get:Provides val dialogLaunchAnimator: DialogLaunchAnimator = mock(),
    @get:Provides val sysuiState: SysUiState = mock(),
    @get:Provides
    val unfoldTransitionProgressProvider: Optional<UnfoldTransitionProgressProvider> =
        Optional.empty(),
    @get:Provides val zenModeController: ZenModeController = mock(),
    @get:Provides val systemUIDialogManager: SystemUIDialogManager = mock(),
    @get:Provides val deviceEntryIconTransitions: Set<DeviceEntryIconTransition> = emptySet(),

    // log buffers
    @get:[Provides BroadcastDispatcherLog]
    val broadcastDispatcherLogger: LogBuffer = mock(),
    @get:[Provides SceneFrameworkLog]
    val sceneLogger: LogBuffer = mock(),
    @get:[Provides BiometricLog]
    val biometricLogger: LogBuffer = mock(),
    @get:Provides val lsShadeTransitionLogger: LSShadeTransitionLogger = mock(),

    // framework mocks
    @get:Provides val activityManager: ActivityManager = mock(),
    @get:Provides val devicePolicyManager: DevicePolicyManager = mock(),
    @get:Provides val displayMetrics: DisplayMetrics = mock(),
    @get:Provides val metricsLogger: MetricsLogger = mock(),
    @get:Provides val userManager: UserManager = mock(),
) {
    @Module
    interface Bindings {
        @Binds
        fun bindStatusBarStateController(
            sysuiStatusBarStateController: SysuiStatusBarStateController,
        ): StatusBarStateController
    }
}

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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.shade

import android.view.WindowManager
import com.android.systemui.assist.AssistManager
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.log.LogBuffer
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.notification.row.NotificationGutsManager
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.windowRootViewVisibilityInteractor
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.phone.statusBarKeyguardViewManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.deviceProvisionedController
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.ExperimentalCoroutinesApi

val Kosmos.shadeControllerSceneImpl by
    Kosmos.Fixture {
        ShadeControllerSceneImpl(
            mainDispatcher = testDispatcher,
            scope = applicationCoroutineScope,
            shadeInteractor = shadeInteractor,
            sceneInteractor = sceneInteractor,
            notificationStackScrollLayout = mock<NotificationStackScrollLayout>(),
            deviceEntryInteractor = deviceEntryInteractor,
            touchLog = mock<LogBuffer>(),
            commandQueue = mock<CommandQueue>(),
            statusBarKeyguardViewManager = mock<StatusBarKeyguardViewManager>(),
            notificationShadeWindowController = mock<NotificationShadeWindowController>(),
            assistManagerLazy = { mock<AssistManager>() },
        )
    }

val Kosmos.shadeControllerImpl by
    Kosmos.Fixture {
        ShadeControllerImpl(
            mock<CommandQueue>(),
            fakeExecutor,
            mock<LogBuffer>(),
            windowRootViewVisibilityInteractor,
            mock<KeyguardStateController>(),
            statusBarStateController,
            statusBarKeyguardViewManager,
            mock<StatusBarWindowController>(),
            deviceProvisionedController,
            mock<NotificationShadeWindowController>(),
            mock<WindowManager>(),
            { mock<ShadeViewController>() },
            { mock<AssistManager>() },
            { mock<NotificationGutsManager>() },
        )
    }
var Kosmos.shadeController: ShadeController by Kosmos.Fixture { shadeControllerImpl }

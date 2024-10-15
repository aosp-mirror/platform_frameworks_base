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

package com.android.systemui.statusbar.core

import com.android.systemui.bouncer.domain.interactor.primaryBouncerInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.mockDemoModeController
import com.android.systemui.plugins.mockPluginDependencyProvider
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.shade.mockNotificationShadeWindowViewController
import com.android.systemui.shade.mockShadeSurface
import com.android.systemui.statusbar.data.repository.fakeStatusBarModeRepository
import com.android.systemui.statusbar.mockNotificationRemoteInputManager
import com.android.systemui.statusbar.phone.mockAutoHideController
import com.android.systemui.statusbar.window.data.repository.statusBarWindowStateRepositoryStore
import com.android.systemui.statusbar.window.fakeStatusBarWindowControllerStore
import com.android.wm.shell.bubbles.bubblesOptional

val Kosmos.statusBarOrchestrator by
    Kosmos.Fixture {
        StatusBarOrchestrator(
            applicationCoroutineScope,
            fakeStatusBarInitializer,
            fakeStatusBarModeRepository,
            fakeStatusBarWindowControllerStore,
            mockDemoModeController,
            mockPluginDependencyProvider,
            mockAutoHideController,
            mockNotificationRemoteInputManager,
            { mockNotificationShadeWindowViewController },
            mockShadeSurface,
            bubblesOptional,
            statusBarWindowStateRepositoryStore,
            powerInteractor,
            primaryBouncerInteractor,
        )
    }

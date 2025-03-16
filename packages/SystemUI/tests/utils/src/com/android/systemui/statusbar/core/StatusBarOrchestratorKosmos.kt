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

import android.content.testableContext
import com.android.systemui.bouncer.domain.interactor.primaryBouncerInteractor
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.display.data.repository.displayScopeRepository
import com.android.systemui.dump.dumpManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.mockDemoModeController
import com.android.systemui.plugins.mockPluginDependencyProvider
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.shade.mockNotificationShadeWindowViewController
import com.android.systemui.shade.mockShadeSurface
import com.android.systemui.statusbar.data.repository.fakeStatusBarModePerDisplayRepository
import com.android.systemui.statusbar.data.repository.lightBarControllerStore
import com.android.systemui.statusbar.data.repository.privacyDotWindowControllerStore
import com.android.systemui.statusbar.data.repository.statusBarModeRepository
import com.android.systemui.statusbar.mockNotificationRemoteInputManager
import com.android.systemui.statusbar.phone.mockAutoHideController
import com.android.systemui.statusbar.phone.multiDisplayAutoHideControllerStore
import com.android.systemui.statusbar.window.data.repository.fakeStatusBarWindowStatePerDisplayRepository
import com.android.systemui.statusbar.window.data.repository.statusBarWindowStateRepositoryStore
import com.android.systemui.statusbar.window.fakeStatusBarWindowController
import com.android.systemui.statusbar.window.statusBarWindowControllerStore
import com.android.wm.shell.bubbles.bubblesOptional

val Kosmos.statusBarOrchestrator by
    Kosmos.Fixture {
        StatusBarOrchestrator(
            testableContext.displayId,
            applicationCoroutineScope,
            fakeStatusBarWindowStatePerDisplayRepository,
            fakeStatusBarModePerDisplayRepository,
            fakeStatusBarInitializer,
            fakeStatusBarWindowController,
            applicationCoroutineScope.coroutineContext,
            mockAutoHideController,
            mockDemoModeController,
            mockPluginDependencyProvider,
            mockNotificationRemoteInputManager,
            { mockNotificationShadeWindowViewController },
            mockShadeSurface,
            bubblesOptional,
            dumpManager,
            powerInteractor,
            primaryBouncerInteractor,
        )
    }

val Kosmos.fakeStatusBarOrchestratorFactory by Kosmos.Fixture { FakeStatusBarOrchestratorFactory() }

var Kosmos.statusBarOrchestratorFactory: StatusBarOrchestrator.Factory by
    Kosmos.Fixture { fakeStatusBarOrchestratorFactory }

val Kosmos.multiDisplayStatusBarStarter by
    Kosmos.Fixture {
        MultiDisplayStatusBarStarter(
            applicationCoroutineScope,
            displayScopeRepository,
            statusBarOrchestratorFactory,
            statusBarWindowStateRepositoryStore,
            statusBarModeRepository,
            displayRepository,
            statusBarInitializerStore,
            statusBarWindowControllerStore,
            statusBarInitializerStore,
            multiDisplayAutoHideControllerStore,
            privacyDotWindowControllerStore,
            lightBarControllerStore,
        )
    }

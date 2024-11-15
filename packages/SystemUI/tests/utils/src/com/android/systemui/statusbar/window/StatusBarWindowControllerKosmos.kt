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

package com.android.systemui.statusbar.window

import android.content.testableContext
import android.view.windowManagerService
import com.android.app.viewcapture.realCaptureAwareWindowManager
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.fragments.fragmentService
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.statusbar.phone.statusBarContentInsetsProvider
import com.android.systemui.statusbar.policy.statusBarConfigurationController
import java.util.Optional

val Kosmos.fakeStatusBarWindowController by Kosmos.Fixture { FakeStatusBarWindowController() }

val Kosmos.statusBarWindowControllerImpl by
    Kosmos.Fixture {
        StatusBarWindowControllerImpl(
            testableContext,
            statusBarWindowViewInflater,
            realCaptureAwareWindowManager,
            statusBarConfigurationController,
            windowManagerService,
            statusBarContentInsetsProvider,
            fragmentService,
            Optional.empty(),
            fakeExecutor,
        )
    }

var Kosmos.statusBarWindowController: StatusBarWindowController by
    Kosmos.Fixture { fakeStatusBarWindowController }

val Kosmos.fakeStatusBarWindowControllerStore by
    Kosmos.Fixture { FakeStatusBarWindowControllerStore() }

var Kosmos.statusBarWindowControllerStore: StatusBarWindowControllerStore by
    Kosmos.Fixture { fakeStatusBarWindowControllerStore }

val Kosmos.fakeStatusBarWindowControllerFactory by
    Kosmos.Fixture { FakeStatusBarWindowControllerFactory() }

var Kosmos.statusBarWindowControllerFactory: StatusBarWindowController.Factory by
    Kosmos.Fixture { fakeStatusBarWindowControllerFactory }

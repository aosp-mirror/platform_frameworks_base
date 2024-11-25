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

import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.statusbar.data.repository.fakeStatusBarModeRepository
import com.android.systemui.statusbar.window.fakeStatusBarWindowControllerStore

val Kosmos.fakeStatusBarInitializer by Kosmos.Fixture { FakeStatusBarInitializer() }

var Kosmos.statusBarInitializer by Kosmos.Fixture { fakeStatusBarInitializer }

val Kosmos.fakeStatusBarInitializerFactory by Kosmos.Fixture { FakeStatusBarInitializerFactory() }

var Kosmos.statusBarInitializerFactory: StatusBarInitializer.Factory by
    Kosmos.Fixture { fakeStatusBarInitializerFactory }

val Kosmos.multiDisplayStatusBarInitializerStore by
    Kosmos.Fixture {
        MultiDisplayStatusBarInitializerStore(
            applicationCoroutineScope,
            displayRepository,
            fakeStatusBarInitializerFactory,
            fakeStatusBarWindowControllerStore,
            fakeStatusBarModeRepository,
        )
    }

val Kosmos.fakeStatusBarInitializerStore by Kosmos.Fixture { FakeStatusBarInitializerStore() }

var Kosmos.statusBarInitializerStore: StatusBarInitializerStore by
    Kosmos.Fixture { fakeStatusBarInitializerStore }

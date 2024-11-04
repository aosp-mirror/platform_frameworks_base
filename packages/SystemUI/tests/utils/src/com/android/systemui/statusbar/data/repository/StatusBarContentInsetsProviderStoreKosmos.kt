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

package com.android.systemui.statusbar.data.repository

import com.android.systemui.cameraProtectionLoaderFactory
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.display.data.repository.displayWindowPropertiesRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.statusbar.phone.statusBarContentInsetsProviderFactory
import com.android.systemui.sysUICutoutProviderFactory

val Kosmos.fakeStatusBarContentInsetsProviderStore by
    Kosmos.Fixture { FakeStatusBarContentInsetsProviderStore() }

val Kosmos.multiDisplayStatusBarContentInsetsProviderStore by
    Kosmos.Fixture {
        MultiDisplayStatusBarContentInsetsProviderStore(
            applicationCoroutineScope,
            displayRepository,
            statusBarContentInsetsProviderFactory,
            displayWindowPropertiesRepository,
            statusBarConfigurationControllerStore,
            sysUICutoutProviderFactory,
            cameraProtectionLoaderFactory,
        )
    }

var Kosmos.statusBarContentInsetsProviderStore: StatusBarContentInsetsProviderStore by
    Kosmos.Fixture { fakeStatusBarContentInsetsProviderStore }

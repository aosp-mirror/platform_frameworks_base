/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.layout

import android.content.applicationContext
import com.android.systemui.SysUICutoutProvider
import com.android.systemui.dump.dumpManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.statusbar.commandline.commandRegistry
import com.android.systemui.statusbar.policy.configurationController
import com.android.systemui.statusbar.policy.fake
import org.mockito.kotlin.mock

val Kosmos.mockStatusBarContentInsetsProvider by
    Kosmos.Fixture { mock<StatusBarContentInsetsProvider>() }

val Kosmos.statusBarContentInsetsProvider by
    Kosmos.Fixture {
        StatusBarContentInsetsProviderImpl(
            applicationContext,
            configurationController.fake,
            dumpManager,
            commandRegistry,
            mock<SysUICutoutProvider>(),
        )
    }

val Kosmos.fakeStatusBarContentInsetsProviderFactory by
    Kosmos.Fixture { FakeStatusBarContentInsetsProviderFactory() }

var Kosmos.statusBarContentInsetsProviderFactory: StatusBarContentInsetsProviderImpl.Factory by
    Kosmos.Fixture { fakeStatusBarContentInsetsProviderFactory }

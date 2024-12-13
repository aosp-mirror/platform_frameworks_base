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

package com.android.systemui.shade.data.repository

import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.display.AnyExternalShadeDisplayPolicy
import com.android.systemui.shade.display.DefaultDisplayShadePolicy
import com.android.systemui.shade.display.ShadeDisplayPolicy
import com.android.systemui.shade.display.StatusBarTouchShadeDisplayPolicy
import com.android.systemui.util.settings.fakeGlobalSettings

val Kosmos.defaultShadeDisplayPolicy: DefaultDisplayShadePolicy by
    Kosmos.Fixture { DefaultDisplayShadePolicy() }

val Kosmos.anyExternalShadeDisplayPolicy: AnyExternalShadeDisplayPolicy by
    Kosmos.Fixture {
        AnyExternalShadeDisplayPolicy(
            bgScope = testScope.backgroundScope,
            displayRepository = displayRepository,
        )
    }

val Kosmos.focusBasedShadeDisplayPolicy: StatusBarTouchShadeDisplayPolicy by
    Kosmos.Fixture {
        StatusBarTouchShadeDisplayPolicy(
            displayRepository = displayRepository,
            backgroundScope = testScope.backgroundScope,
            keyguardRepository = keyguardRepository,
            shadeOnDefaultDisplayWhenLocked = false,
        )
    }

val Kosmos.shadeDisplaysRepository: MutableShadeDisplaysRepository by
    Kosmos.Fixture {
        ShadeDisplaysRepositoryImpl(
            bgScope = testScope.backgroundScope,
            globalSettings = fakeGlobalSettings,
            policies = shadeDisplayPolicies,
            defaultPolicy = defaultShadeDisplayPolicy,
        )
    }

val Kosmos.shadeDisplayPolicies: Set<ShadeDisplayPolicy> by
    Kosmos.Fixture {
        setOf(
            defaultShadeDisplayPolicy,
            anyExternalShadeDisplayPolicy,
            focusBasedShadeDisplayPolicy,
        )
    }

val Kosmos.fakeShadeDisplaysRepository: FakeShadeDisplayRepository by
    Kosmos.Fixture { FakeShadeDisplayRepository() }

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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.resolver

import com.android.compose.animation.scene.SceneKey
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardEnabledInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.shade.domain.interactor.shadeInteractor
import kotlinx.coroutines.ExperimentalCoroutinesApi

val Kosmos.sceneFamilyResolvers: Map<SceneKey, SceneResolver>
    get() =
        mapOf(
            SceneFamilies.Home to homeSceneFamilyResolver,
            SceneFamilies.NotifShade to notifShadeSceneFamilyResolver,
            SceneFamilies.QuickSettings to quickSettingsSceneFamilyResolver,
        )

val Kosmos.homeSceneFamilyResolver by
    Kosmos.Fixture {
        HomeSceneFamilyResolver(
            applicationScope = applicationCoroutineScope,
            deviceEntryInteractor = deviceEntryInteractor,
            keyguardEnabledInteractor = keyguardEnabledInteractor,
        )
    }

val Kosmos.notifShadeSceneFamilyResolver by
    Kosmos.Fixture {
        NotifShadeSceneFamilyResolver(
            applicationScope = applicationCoroutineScope,
            shadeInteractor = shadeInteractor,
        )
    }

val Kosmos.quickSettingsSceneFamilyResolver by
    Kosmos.Fixture {
        QuickSettingsSceneFamilyResolver(
            applicationScope = applicationCoroutineScope,
            shadeInteractor = shadeInteractor,
        )
    }

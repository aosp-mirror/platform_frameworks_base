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

package com.android.systemui.scene.domain.interactor

import com.android.compose.animation.scene.SceneKey
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.domain.resolver.HomeSceneFamilyResolver
import com.android.systemui.scene.domain.resolver.SceneResolver
import com.android.systemui.scene.shared.logger.sceneLogger
import com.android.systemui.scene.shared.model.SceneFamilies

val Kosmos.sceneInteractor by
    Kosmos.Fixture {
        SceneInteractor(
            applicationScope = applicationCoroutineScope,
            repository = sceneContainerRepository,
            logger = sceneLogger,
            sceneFamilyResolvers = { sceneFamilyResolvers },
            deviceUnlockedInteractor = deviceUnlockedInteractor,
        )
    }

val Kosmos.sceneFamilyResolvers: Map<SceneKey, SceneResolver>
    get() = mapOf(SceneFamilies.Home to homeSceneFamilyResolver)

val Kosmos.homeSceneFamilyResolver by
    Kosmos.Fixture {
        HomeSceneFamilyResolver(
            applicationScope = applicationCoroutineScope,
            deviceEntryInteractor = deviceEntryInteractor,
        )
    }

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

package com.android.systemui.scene.domain.resolver

import com.android.compose.animation.scene.SceneKey
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class QuickSettingsSceneFamilyResolver
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    shadeInteractor: ShadeInteractor,
) : SceneResolver {
    override val targetFamily: SceneKey = SceneFamilies.QuickSettings

    override val resolvedScene: StateFlow<SceneKey> =
        shadeInteractor.shadeMode
            .map(::quickSettingsScene)
            .stateIn(
                applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = quickSettingsScene(shadeInteractor.shadeMode.value),
            )

    override fun includesScene(scene: SceneKey): Boolean = scene in quickSettingsScenes

    private fun quickSettingsScene(shadeMode: ShadeMode) =
        when (shadeMode) {
            is ShadeMode.Single -> Scenes.QuickSettings
            is ShadeMode.Dual -> Scenes.QuickSettingsShade
            is ShadeMode.Split -> Scenes.Shade
        }

    companion object {
        val quickSettingsScenes =
            setOf(
                Scenes.QuickSettings,
                Scenes.QuickSettingsShade,
                Scenes.Shade,
            )
    }
}

@Module
interface QuickSettingsSceneFamilyResolverModule {
    @Binds @IntoSet fun bindResolver(interactor: QuickSettingsSceneFamilyResolver): SceneResolver
}

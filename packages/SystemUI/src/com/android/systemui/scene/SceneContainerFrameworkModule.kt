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

package com.android.systemui.scene

import com.android.systemui.CoreStartable
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlagsModule
import com.android.systemui.notifications.ui.composable.NotificationsShadeSessionModule
import com.android.systemui.scene.domain.SceneDomainModule
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor
import com.android.systemui.scene.domain.resolver.HomeSceneFamilyResolverModule
import com.android.systemui.scene.domain.resolver.NotifShadeSceneFamilyResolverModule
import com.android.systemui.scene.domain.resolver.QuickSettingsSceneFamilyResolverModule
import com.android.systemui.scene.domain.startable.SceneContainerStartable
import com.android.systemui.scene.domain.startable.ScrimStartable
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.shared.flag.DualShade
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/** Scene framework Dagger module suitable for AOSP. */
@Module(
    includes =
        [
            BouncerSceneModule::class,
            CommunalSceneModule::class,
            ComposeBouncerFlagsModule::class,
            EmptySceneModule::class,
            GoneSceneModule::class,
            LockscreenSceneModule::class,
            QuickSettingsSceneModule::class,
            ShadeSceneModule::class,
            QuickSettingsShadeSceneModule::class,
            NotificationsShadeSceneModule::class,
            NotificationsShadeSessionModule::class,
            SceneDomainModule::class,

            // List SceneResolver modules for supported SceneFamilies
            HomeSceneFamilyResolverModule::class,
            NotifShadeSceneFamilyResolverModule::class,
            QuickSettingsSceneFamilyResolverModule::class,
        ],
)
interface SceneContainerFrameworkModule {

    @Binds
    @IntoMap
    @ClassKey(SceneContainerStartable::class)
    fun containerStartable(impl: SceneContainerStartable): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(ScrimStartable::class)
    fun scrimStartable(impl: ScrimStartable): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(WindowRootViewVisibilityInteractor::class)
    fun bindWindowRootViewVisibilityInteractor(
        impl: WindowRootViewVisibilityInteractor
    ): CoreStartable

    companion object {

        @Provides
        fun containerConfig(): SceneContainerConfig {
            return SceneContainerConfig(
                // Note that this list is in z-order. The first one is the bottom-most and the last
                // one is top-most.
                sceneKeys =
                    listOfNotNull(
                        Scenes.Gone,
                        Scenes.Communal,
                        Scenes.Lockscreen,
                        Scenes.Bouncer,
                        Scenes.QuickSettings.takeUnless { DualShade.isEnabled },
                        Scenes.QuickSettingsShade.takeIf { DualShade.isEnabled },
                        Scenes.NotificationsShade.takeIf { DualShade.isEnabled },
                        Scenes.Shade.takeUnless { DualShade.isEnabled },
                    ),
                initialSceneKey = Scenes.Lockscreen,
                navigationDistances =
                    mapOf(
                            Scenes.Gone to 0,
                            Scenes.Lockscreen to 0,
                            Scenes.Communal to 1,
                            Scenes.NotificationsShade to 2.takeIf { DualShade.isEnabled },
                            Scenes.Shade to 2.takeUnless { DualShade.isEnabled },
                            Scenes.QuickSettingsShade to 3.takeIf { DualShade.isEnabled },
                            Scenes.QuickSettings to 3.takeUnless { DualShade.isEnabled },
                            Scenes.Bouncer to 4,
                        )
                        .filterValues { it != null }
                        .mapValues { checkNotNull(it.value) }
            )
        }
    }
}

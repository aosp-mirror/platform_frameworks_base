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

import androidx.compose.ui.unit.dp
import com.android.systemui.CoreStartable
import com.android.systemui.notifications.ui.composable.NotificationsShadeSessionModule
import com.android.systemui.scene.domain.SceneDomainModule
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor
import com.android.systemui.scene.domain.resolver.HomeSceneFamilyResolverModule
import com.android.systemui.scene.domain.startable.KeyguardStateCallbackStartable
import com.android.systemui.scene.domain.startable.SceneContainerStartable
import com.android.systemui.scene.domain.startable.ScrimStartable
import com.android.systemui.scene.domain.startable.StatusBarStartable
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.viewmodel.SplitEdgeDetector
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.flag.DualShade
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/** Scene framework Dagger module suitable for variants that want to exclude "keyguard" scenes. */
@Module(
    includes =
        [
            EmptySceneModule::class,
            GoneSceneModule::class,
            NotificationsShadeOverlayModule::class,
            NotificationsShadeSessionModule::class,
            QuickSettingsShadeOverlayModule::class,
            QuickSettingsSceneModule::class,
            ShadeSceneModule::class,
            SceneDomainModule::class,

            // List SceneResolver modules for supported SceneFamilies
            HomeSceneFamilyResolverModule::class,
        ]
)
interface KeyguardlessSceneContainerFrameworkModule {

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
    @ClassKey(StatusBarStartable::class)
    fun statusBarStartable(impl: StatusBarStartable): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(KeyguardStateCallbackStartable::class)
    fun keyguardStateCallbackStartable(impl: KeyguardStateCallbackStartable): CoreStartable

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
                // Note that this list is in z-order. The first one is the bottom-most and the
                // last one is top-most.
                sceneKeys =
                    listOfNotNull(
                        Scenes.Gone,
                        Scenes.QuickSettings.takeUnless { DualShade.isEnabled },
                        Scenes.Shade.takeUnless { DualShade.isEnabled },
                    ),
                initialSceneKey = Scenes.Gone,
                overlayKeys =
                    listOfNotNull(
                        Overlays.NotificationsShade.takeIf { DualShade.isEnabled },
                        Overlays.QuickSettingsShade.takeIf { DualShade.isEnabled },
                    ),
                navigationDistances =
                    mapOf(
                            Scenes.Gone to 0,
                            Scenes.Shade to 1.takeUnless { DualShade.isEnabled },
                            Scenes.QuickSettings to 2.takeUnless { DualShade.isEnabled },
                        )
                        .filterValues { it != null }
                        .mapValues { checkNotNull(it.value) },
            )
        }

        @Provides
        fun splitEdgeDetector(shadeInteractor: ShadeInteractor): SplitEdgeDetector {
            return SplitEdgeDetector(
                topEdgeSplitFraction = shadeInteractor::getTopEdgeSplitFraction,
                // TODO(b/338577208): This should be 60dp at the top in the dual-shade UI. Better to
                //  replace this constant with dynamic window insets.
                edgeSize = 40.dp,
            )
        }
    }
}

/*
 * Copyright 2023 The Android Open Source Project
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

import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.KeyguardViewConfigurator
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.qualifiers.KeyguardRootView
import com.android.systemui.keyguard.shared.model.LockscreenSceneBlueprint
import com.android.systemui.keyguard.ui.composable.LockscreenContent
import com.android.systemui.keyguard.ui.composable.LockscreenScene
import com.android.systemui.keyguard.ui.composable.LockscreenSceneBlueprintModule
import com.android.systemui.keyguard.ui.composable.blueprint.ComposableLockscreenSceneBlueprint
import com.android.systemui.keyguard.ui.viewmodel.LockscreenContentViewModel
import com.android.systemui.scene.shared.model.Scene
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi

@Module(
    includes =
        [
            LockscreenSceneBlueprintModule::class,
        ],
)
interface LockscreenSceneModule {

    @Binds @IntoSet fun lockscreenScene(scene: LockscreenScene): Scene

    companion object {

        @OptIn(ExperimentalCoroutinesApi::class)
        @Provides
        @SysUISingleton
        @KeyguardRootView
        fun viewProvider(
            configurator: Provider<KeyguardViewConfigurator>,
        ): () -> View {
            return { configurator.get().getKeyguardRootView() }
        }

        @Provides
        fun providesLockscreenBlueprints(
            blueprints: Set<@JvmSuppressWildcards ComposableLockscreenSceneBlueprint>
        ): Set<LockscreenSceneBlueprint> {
            return blueprints
        }

        @Provides
        fun providesLockscreenContent(
            viewModel: LockscreenContentViewModel,
            blueprints: Set<@JvmSuppressWildcards ComposableLockscreenSceneBlueprint>,
            clockInteractor: KeyguardClockInteractor,
        ): LockscreenContent {
            return LockscreenContent(viewModel, blueprints, clockInteractor)
        }
    }
}

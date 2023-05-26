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

package com.android.systemui.scene.ui.composable

import android.content.Context
import com.android.systemui.bouncer.ui.composable.BouncerScene
import com.android.systemui.bouncer.ui.viewmodel.BouncerViewModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.ui.composable.LockscreenScene
import com.android.systemui.keyguard.ui.viewmodel.LockscreenSceneViewModel
import com.android.systemui.qs.ui.composable.QuickSettingsScene
import com.android.systemui.qs.ui.viewmodel.QuickSettingsSceneViewModel
import com.android.systemui.scene.shared.model.Scene
import com.android.systemui.scene.shared.model.SceneContainerNames
import com.android.systemui.shade.ui.composable.ShadeScene
import com.android.systemui.shade.ui.viewmodel.ShadeSceneViewModel
import com.android.systemui.statusbar.phone.SystemUIDialog
import dagger.Module
import dagger.Provides
import javax.inject.Named
import kotlinx.coroutines.CoroutineScope

@Module
object SceneModule {
    @Provides
    @Named(SceneContainerNames.SYSTEM_UI_DEFAULT)
    fun scenes(
        @Named(SceneContainerNames.SYSTEM_UI_DEFAULT) bouncer: BouncerScene,
        @Named(SceneContainerNames.SYSTEM_UI_DEFAULT) gone: GoneScene,
        @Named(SceneContainerNames.SYSTEM_UI_DEFAULT) lockScreen: LockscreenScene,
        @Named(SceneContainerNames.SYSTEM_UI_DEFAULT) qs: QuickSettingsScene,
        @Named(SceneContainerNames.SYSTEM_UI_DEFAULT) shade: ShadeScene,
    ): Set<Scene> {
        return setOf(
            bouncer,
            gone,
            lockScreen,
            qs,
            shade,
        )
    }

    @Provides
    @SysUISingleton
    @Named(SceneContainerNames.SYSTEM_UI_DEFAULT)
    fun bouncerScene(
        @Application context: Context,
        viewModelFactory: BouncerViewModel.Factory,
    ): BouncerScene {
        return BouncerScene(
            viewModel =
                viewModelFactory.create(
                    containerName = SceneContainerNames.SYSTEM_UI_DEFAULT,
                ),
            dialogFactory = { SystemUIDialog(context) },
        )
    }

    @Provides
    @SysUISingleton
    @Named(SceneContainerNames.SYSTEM_UI_DEFAULT)
    fun goneScene(): GoneScene {
        return GoneScene()
    }

    @Provides
    @SysUISingleton
    @Named(SceneContainerNames.SYSTEM_UI_DEFAULT)
    fun lockscreenScene(
        @Application applicationScope: CoroutineScope,
        viewModelFactory: LockscreenSceneViewModel.Factory,
    ): LockscreenScene {
        return LockscreenScene(
            applicationScope = applicationScope,
            viewModel =
                viewModelFactory.create(
                    containerName = SceneContainerNames.SYSTEM_UI_DEFAULT,
                ),
        )
    }

    @Provides
    @SysUISingleton
    @Named(SceneContainerNames.SYSTEM_UI_DEFAULT)
    fun quickSettingsScene(
        viewModelFactory: QuickSettingsSceneViewModel.Factory,
    ): QuickSettingsScene {
        return QuickSettingsScene(
            viewModel =
                viewModelFactory.create(
                    containerName = SceneContainerNames.SYSTEM_UI_DEFAULT,
                ),
        )
    }

    @Provides
    @SysUISingleton
    @Named(SceneContainerNames.SYSTEM_UI_DEFAULT)
    fun shadeScene(
        @Application applicationScope: CoroutineScope,
        viewModelFactory: ShadeSceneViewModel.Factory,
    ): ShadeScene {
        return ShadeScene(
            applicationScope = applicationScope,
            viewModel =
                viewModelFactory.create(
                    containerName = SceneContainerNames.SYSTEM_UI_DEFAULT,
                ),
        )
    }
}

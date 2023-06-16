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

package com.android.systemui.scene.startable

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.shared.model.Scene
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneContainerNames
import com.android.systemui.scene.ui.view.SceneWindowRootView
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import javax.inject.Named

@SysUISingleton
class SceneContainerStartable
@Inject
constructor(
    private val view: WindowRootView,
    @Named(SceneContainerNames.SYSTEM_UI_DEFAULT) private val viewModel: SceneContainerViewModel,
    @Named(SceneContainerNames.SYSTEM_UI_DEFAULT) private val containerConfig: SceneContainerConfig,
    @Named(SceneContainerNames.SYSTEM_UI_DEFAULT)
    private val scenes: Set<@JvmSuppressWildcards Scene>,
) : CoreStartable {

    override fun start() {
        (view as? SceneWindowRootView)?.init(
            viewModel = viewModel,
            containerConfig = containerConfig,
            scenes = scenes,
        )
    }
}

@Module
interface SceneContainerStartableModule {
    @Binds
    @IntoMap
    @ClassKey(SceneContainerStartable::class)
    fun bind(impl: SceneContainerStartable): CoreStartable
}

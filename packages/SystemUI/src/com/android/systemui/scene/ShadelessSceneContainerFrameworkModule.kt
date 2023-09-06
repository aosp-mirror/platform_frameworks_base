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

import com.android.systemui.scene.shared.flag.SceneContainerFlagsModule
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneKey
import dagger.Module
import dagger.Provides

/** Scene framework Dagger module suitable for variants that want to exclude "shade" scenes. */
@Module(
    includes =
        [
            BouncerSceneModule::class,
            EmptySceneModule::class,
            GoneSceneModule::class,
            LockscreenSceneModule::class,
            SceneContainerFlagsModule::class,
        ],
)
object ShadelessSceneContainerFrameworkModule {

    // TODO(b/298229861): provide a version of SceneContainerStartable without shade and qs.

    @Provides
    fun containerConfig(): SceneContainerConfig {
        return SceneContainerConfig(
            // Note that this list is in z-order. The first one is the bottom-most and the
            // last one is top-most.
            sceneKeys =
                listOf(
                    SceneKey.Gone,
                    SceneKey.Lockscreen,
                    SceneKey.Bouncer,
                ),
            initialSceneKey = SceneKey.Lockscreen,
        )
    }
}

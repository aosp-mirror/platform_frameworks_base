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

package com.android.systemui.scene.shared.flag

import dagger.Binds
import dagger.Module
import dagger.Provides

class FakeSceneContainerFlags(
    var enabled: Boolean = false,
) : SceneContainerFlags {

    override fun isEnabled(): Boolean {
        return enabled
    }

    override fun requirementDescription(): String {
        return ""
    }
}

@Module(includes = [FakeSceneContainerFlagsModule.Bindings::class])
class FakeSceneContainerFlagsModule(
    @get:Provides val sceneContainerFlags: FakeSceneContainerFlags = FakeSceneContainerFlags(),
) {
    @Module
    interface Bindings {
        @Binds fun bindFake(fake: FakeSceneContainerFlags): SceneContainerFlags
    }
}

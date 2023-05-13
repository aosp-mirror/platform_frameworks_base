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

import com.android.systemui.bouncer.ui.composable.BouncerScene
import com.android.systemui.keyguard.ui.composable.LockScreenScene
import com.android.systemui.qs.ui.composable.QuickSettingsScene
import com.android.systemui.scene.shared.model.Scene
import com.android.systemui.shade.ui.composable.ShadeScene
import dagger.Module
import dagger.Provides

@Module
object SceneModule {
    @Provides
    fun scenes(
        bouncer: BouncerScene,
        gone: GoneScene,
        lockScreen: LockScreenScene,
        qs: QuickSettingsScene,
        shade: ShadeScene,
    ): Set<Scene> {
        return setOf(
            bouncer,
            gone,
            lockScreen,
            qs,
            shade,
        )
    }
}

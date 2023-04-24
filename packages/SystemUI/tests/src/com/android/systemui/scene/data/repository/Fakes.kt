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

package com.android.systemui.scene.data.repository

import com.android.systemui.scene.data.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneKey

fun fakeSceneContainerRepository(
    containerConfigurations: Set<SceneContainerConfig> =
        setOf(
            fakeSceneContainerConfig("container1"),
            fakeSceneContainerConfig("container2"),
        )
): SceneContainerRepository {
    return SceneContainerRepository(containerConfigurations)
}

fun fakeSceneKeys(): List<SceneKey> {
    return listOf(
        SceneKey.QuickSettings,
        SceneKey.Shade,
        SceneKey.LockScreen,
        SceneKey.Bouncer,
        SceneKey.Gone,
    )
}

fun fakeSceneContainerConfig(
    name: String,
    sceneKeys: List<SceneKey> = fakeSceneKeys(),
): SceneContainerConfig {
    return SceneContainerConfig(
        name = name,
        sceneKeys = sceneKeys,
        initialSceneKey = SceneKey.LockScreen,
    )
}

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

package com.android.systemui.communal.shared.model

import com.android.compose.animation.scene.SceneKey
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes

/** Definition of the possible scenes for the communal UI. */
@Deprecated(
    "CommunalScenes are deprecated when SceneContainerFlag is enabled. " +
        "Use com.android.systemui.scene.shared.model.Scenes instead. " +
        "Use SceneKey.toSceneContainerSceneKey() to map legacy scenes to scene container scenes. " +
        "Use SceneKey.isCommunal() to check whether a scene is a communal scene."
)
object CommunalScenes {
    /** The default scene, shows nothing and is only there to allow swiping to communal. */
    @JvmField val Blank = SceneKey("blank")

    /** The communal scene containing the hub UI. */
    @JvmField val Communal = SceneKey("communal")

    @JvmField val Default = Blank

    private fun SceneKey.isCommunalScene(): Boolean {
        return this == Blank || this == Communal
    }

    /**
     * Maps a legacy communal scene to a scene in the scene container.
     *
     * The rules are simple:
     * - A legacy communal scene maps to a communal scene in the Scene Transition Framework (STF).
     * - A legacy blank scene means that the communal scene layout does not render anything so
     *   whatever is beneath the layout is shown. That usually means lockscreen or dream, both in
     *   STL are represented by the home scene family.
     */
    fun SceneKey.toSceneContainerSceneKey(): SceneKey {
        if (!isCommunalScene() || !SceneContainerFlag.isEnabled) {
            return this
        }

        return when (this) {
            Communal -> Scenes.Communal
            Blank -> SceneFamilies.Home
            else -> throw Throwable("Unrecognized communal scene: $this")
        }
    }

    /** Checks whether this is a communal scene based on whether [SceneContainerFlag] is enabled. */
    fun SceneKey.isCommunal(): Boolean {
        return if (SceneContainerFlag.isEnabled) this == Scenes.Communal else this == Communal
    }
}

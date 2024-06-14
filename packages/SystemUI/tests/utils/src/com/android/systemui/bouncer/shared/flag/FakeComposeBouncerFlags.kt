/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.bouncer.shared.flag

import com.android.systemui.scene.shared.flag.SceneContainerFlags

class FakeComposeBouncerFlags(
    private val sceneContainerFlags: SceneContainerFlags,
    var composeBouncerEnabled: Boolean = false
) : ComposeBouncerFlags {
    override fun isComposeBouncerOrSceneContainerEnabled(): Boolean {
        return sceneContainerFlags.isEnabled() || composeBouncerEnabled
    }

    @Deprecated(
        "Avoid using this, this is meant to be used only by the glue code " +
            "that includes compose bouncer in legacy keyguard.",
        replaceWith = ReplaceWith("isComposeBouncerOrSceneContainerEnabled()")
    )
    override fun isOnlyComposeBouncerEnabled(): Boolean = composeBouncerEnabled
}

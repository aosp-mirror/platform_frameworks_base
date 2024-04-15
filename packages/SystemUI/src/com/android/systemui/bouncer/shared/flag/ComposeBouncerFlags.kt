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

import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import dagger.Module
import dagger.Provides

interface ComposeBouncerFlags {

    /**
     * Returns `true` if the Compose bouncer is enabled or if the scene container framework is
     * enabled; `false` otherwise.
     */
    fun isComposeBouncerOrSceneContainerEnabled(): Boolean

    /**
     * Returns `true` if only compose bouncer is enabled and scene container framework is not
     * enabled.
     */
    @Deprecated(
        "Avoid using this, this is meant to be used only by the glue code " +
            "that includes compose bouncer in legacy keyguard.",
        replaceWith = ReplaceWith("isComposeBouncerOrSceneContainerEnabled()")
    )
    fun isOnlyComposeBouncerEnabled(): Boolean
}

class ComposeBouncerFlagsImpl() : ComposeBouncerFlags {

    override fun isComposeBouncerOrSceneContainerEnabled(): Boolean {
        return SceneContainerFlag.isEnabled || Flags.composeBouncer()
    }

    @Deprecated(
        "Avoid using this, this is meant to be used only by the glue code " +
            "that includes compose bouncer in legacy keyguard.",
        replaceWith = ReplaceWith("isComposeBouncerOrSceneContainerEnabled()")
    )
    override fun isOnlyComposeBouncerEnabled(): Boolean {
        return !SceneContainerFlag.isEnabled && Flags.composeBouncer()
    }
}

@Module
object ComposeBouncerFlagsModule {
    @Provides
    @SysUISingleton
    fun impl(): ComposeBouncerFlags {
        return ComposeBouncerFlagsImpl()
    }
}

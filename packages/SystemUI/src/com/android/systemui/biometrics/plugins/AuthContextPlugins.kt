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

package com.android.systemui.biometrics.plugins

import com.android.systemui.plugins.AuthContextPlugin
import com.android.systemui.plugins.PluginManager

/** Wrapper interface for registering & forwarding events to all available [AuthContextPlugin]s. */
interface AuthContextPlugins {
    /** Finds and actives all plugins via SysUI's [PluginManager] (should be called at startup). */
    fun activate()

    /**
     * Interact with all registered plugins.
     *
     * The provided [block] will be repeated for each available plugin.
     */
    suspend fun use(block: (AuthContextPlugin) -> Unit)

    /**
     * Like [use] but when no existing coroutine context is available.
     *
     * The [block] will be run on SysUI's general background context and can, optionally, be
     * confined to [runOnMain] (defaults to a background thread).
     */
    fun useInBackground(runOnMain: Boolean = false, block: (AuthContextPlugin) -> Unit)
}

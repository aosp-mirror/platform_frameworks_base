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

package com.android.systemui.scene.session.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Data store for [Session][com.android.systemui.scene.session.ui.composable.Session]. */
class SessionStorage {
    private var _storage by mutableStateOf(hashMapOf<String, StorageEntry>())

    /**
     * Data store containing all state retained for invocations of
     * [rememberSession][com.android.systemui.scene.session.ui.composable.Session.rememberSession]
     */
    val storage: MutableMap<String, StorageEntry>
        get() = _storage

    /**
     * Storage for an individual invocation of
     * [rememberSession][com.android.systemui.scene.session.ui.composable.Session.rememberSession]
     */
    class StorageEntry(val keys: Array<out Any?>, var stored: Any?)

    /** Clears the data store; any downstream usage within `@Composable`s will be recomposed. */
    fun clear() {
        _storage = hashMapOf()
    }
}

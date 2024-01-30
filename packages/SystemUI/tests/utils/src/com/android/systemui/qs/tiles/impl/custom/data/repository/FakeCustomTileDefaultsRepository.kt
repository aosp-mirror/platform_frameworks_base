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

package com.android.systemui.qs.tiles.impl.custom.data.repository

import android.content.ComponentName
import android.os.UserHandle
import com.android.systemui.qs.tiles.impl.custom.data.entity.CustomTileDefaults
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull

class FakeCustomTileDefaultsRepository : CustomTileDefaultsRepository {

    private val defaults: MutableMap<DefaultsKey, CustomTileDefaults> = mutableMapOf()
    private val defaultsFlow =
        MutableSharedFlow<DefaultsRequest>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    private val mutableDefaultsRequests: MutableList<DefaultsRequest> = mutableListOf()
    val defaultsRequests: List<DefaultsRequest> = mutableDefaultsRequests

    override fun defaults(user: UserHandle): Flow<CustomTileDefaults> =
        defaultsFlow
            .distinctUntilChanged { old, new ->
                if (new.force) {
                    false
                } else {
                    old == new
                }
            }
            .mapNotNull { defaults[DefaultsKey(it.user, it.componentName)] }

    override fun requestNewDefaults(
        user: UserHandle,
        componentName: ComponentName,
        force: Boolean
    ) {
        val request = DefaultsRequest(user, componentName, force)
        mutableDefaultsRequests.add(request)
        defaultsFlow.tryEmit(request)
    }

    fun putDefaults(
        user: UserHandle,
        componentName: ComponentName,
        customTileDefaults: CustomTileDefaults,
    ) {
        defaults[DefaultsKey(user, componentName)] = customTileDefaults
    }

    fun removeDefaults(user: UserHandle, componentName: ComponentName) {
        defaults.remove(DefaultsKey(user, componentName))
    }

    data class DefaultsRequest(
        val user: UserHandle,
        val componentName: ComponentName,
        val force: Boolean = false,
    )

    private data class DefaultsKey(val user: UserHandle, val componentName: ComponentName)
}

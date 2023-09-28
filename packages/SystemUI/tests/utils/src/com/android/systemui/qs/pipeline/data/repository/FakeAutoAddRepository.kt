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

package com.android.systemui.qs.pipeline.data.repository

import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.shared.TileSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeAutoAddRepository : AutoAddRepository {

    private val autoAddedTilesPerUser = mutableMapOf<Int, MutableStateFlow<Set<TileSpec>>>()

    override suspend fun autoAddedTiles(userId: Int): StateFlow<Set<TileSpec>> {
        return getFlow(userId)
    }

    override suspend fun markTileAdded(userId: Int, spec: TileSpec) {
        if (spec == TileSpec.Invalid) return
        with(getFlow(userId)) { value = value.toMutableSet().apply { add(spec) } }
    }

    override suspend fun unmarkTileAdded(userId: Int, spec: TileSpec) {
        with(getFlow(userId)) { value = value.toMutableSet().apply { remove(spec) } }
    }

    private fun getFlow(userId: Int): MutableStateFlow<Set<TileSpec>> =
        autoAddedTilesPerUser.getOrPut(userId) { MutableStateFlow(emptySet()) }

    override suspend fun reconcileRestore(restoreData: RestoreData) {
        with(getFlow(restoreData.userId)) { value = value + restoreData.restoredAutoAddedTiles }
    }
}

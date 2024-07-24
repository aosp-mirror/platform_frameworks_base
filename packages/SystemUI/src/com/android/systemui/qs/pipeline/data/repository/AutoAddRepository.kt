/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.data.repository

import android.util.SparseArray
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.shared.TileSpec
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Repository to track what QS tiles have been auto-added */
interface AutoAddRepository {

    /** Flow of tiles that have been auto-added */
    suspend fun autoAddedTiles(userId: Int): Flow<Set<TileSpec>>

    /** Mark a tile as having been auto-added */
    suspend fun markTileAdded(userId: Int, spec: TileSpec)

    /**
     * Unmark a tile as having been auto-added. This is used for tiles that can be auto-added
     * multiple times.
     */
    suspend fun unmarkTileAdded(userId: Int, spec: TileSpec)

    suspend fun reconcileRestore(restoreData: RestoreData)
}

/**
 * Implementation of [AutoAddRepository] that delegates to an instance of [UserAutoAddRepository]
 * for each user.
 */
@SysUISingleton
class AutoAddSettingRepository
@Inject
constructor(
    private val userAutoAddRepositoryFactory: UserAutoAddRepository.Factory,
) : AutoAddRepository {

    private val userAutoAddRepositories = SparseArray<UserAutoAddRepository>()

    override suspend fun autoAddedTiles(userId: Int): Flow<Set<TileSpec>> {
        if (userId !in userAutoAddRepositories) {
            val repository = userAutoAddRepositoryFactory.create(userId)
            userAutoAddRepositories.put(userId, repository)
        }
        return userAutoAddRepositories.get(userId).autoAdded()
    }

    override suspend fun markTileAdded(userId: Int, spec: TileSpec) {
        userAutoAddRepositories.get(userId)?.markTileAdded(spec)
    }

    override suspend fun unmarkTileAdded(userId: Int, spec: TileSpec) {
        userAutoAddRepositories.get(userId)?.unmarkTileAdded(spec)
    }

    override suspend fun reconcileRestore(restoreData: RestoreData) {
        userAutoAddRepositories.get(restoreData.userId)?.reconcileRestore(restoreData)
    }
}

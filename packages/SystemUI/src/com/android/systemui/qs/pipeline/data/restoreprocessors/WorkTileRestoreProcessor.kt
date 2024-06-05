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

package com.android.systemui.qs.pipeline.data.restoreprocessors

import android.os.UserHandle
import android.util.SparseIntArray
import androidx.annotation.GuardedBy
import androidx.core.util.getOrDefault
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.data.model.RestoreProcessor
import com.android.systemui.qs.pipeline.data.repository.QSSettingsRestoredRepository
import com.android.systemui.qs.pipeline.data.repository.TileSpecRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.WorkModeTile
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Processor for restore data for work tile.
 *
 * It will indicate when auto-add tracking may be removed for a user. This may be necessary if the
 * tile will be destroyed due to being not available, but needs to be added once work profile is
 * enabled (after restore), in the same position as it was in the restored data.
 */
@SysUISingleton
class WorkTileRestoreProcessor @Inject constructor() : RestoreProcessor {

    @GuardedBy("lastRestorePosition") private val lastRestorePosition = SparseIntArray()

    private val _removeTrackingForUser =
        MutableSharedFlow<Int>(extraBufferCapacity = QSSettingsRestoredRepository.BUFFER_CAPACITY)

    /**
     * Flow indicating that we may need to remove auto-add tracking for the work tile for a given
     * user.
     */
    fun removeTrackingForUser(userHandle: UserHandle): Flow<Unit> {
        return _removeTrackingForUser.filter { it == userHandle.identifier }.map {}
    }

    override suspend fun postProcessRestore(restoreData: RestoreData) {
        if (TILE_SPEC in restoreData.restoredTiles) {
            synchronized(lastRestorePosition) {
                lastRestorePosition.put(
                    restoreData.userId,
                    restoreData.restoredTiles.indexOf(TILE_SPEC)
                )
            }
            _removeTrackingForUser.emit(restoreData.userId)
        }
    }

    fun pollLastPosition(userId: Int): Int {
        return synchronized(lastRestorePosition) {
            lastRestorePosition.getOrDefault(userId, TileSpecRepository.POSITION_AT_END).also {
                lastRestorePosition.delete(userId)
            }
        }
    }

    companion object {
        private val TILE_SPEC = TileSpec.create(WorkModeTile.TILE_SPEC)
    }
}

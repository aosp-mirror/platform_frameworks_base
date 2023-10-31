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

import android.os.UserHandle
import android.service.quicksettings.Tile
import com.android.systemui.qs.external.FakeCustomTileStatePersister
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.impl.custom.data.entity.CustomTileDefaults
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow

class FakeCustomTileRepository(
    tileSpec: TileSpec.CustomTileSpec,
    customTileStatePersister: FakeCustomTileStatePersister,
    testBackgroundContext: CoroutineContext,
) : CustomTileRepository {

    private val realDelegate: CustomTileRepository =
        CustomTileRepositoryImpl(
            tileSpec,
            customTileStatePersister,
            testBackgroundContext,
        )

    override suspend fun restoreForTheUserIfNeeded(user: UserHandle, isPersistable: Boolean) =
        realDelegate.restoreForTheUserIfNeeded(user, isPersistable)

    override fun getTiles(user: UserHandle): Flow<Tile> = realDelegate.getTiles(user)

    override fun getTile(user: UserHandle): Tile? = realDelegate.getTile(user)

    override suspend fun updateWithTile(
        user: UserHandle,
        newTile: Tile,
        isPersistable: Boolean,
    ) = realDelegate.updateWithTile(user, newTile, isPersistable)

    override suspend fun updateWithDefaults(
        user: UserHandle,
        defaults: CustomTileDefaults,
        isPersistable: Boolean,
    ) = realDelegate.updateWithDefaults(user, defaults, isPersistable)
}

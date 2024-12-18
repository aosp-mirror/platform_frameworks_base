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

package com.android.systemui.qs.panels.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.qs.QSFactory
import com.android.systemui.qs.pipeline.shared.QSPipelineFlagsRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Given a list of tiles, it determines which of those are unavailable.
 */
@SysUISingleton
class TilesAvailabilityInteractor
@Inject
constructor(
        private val newTilesAvailabilityInteractor: NewTilesAvailabilityInteractor,
        private val qsFactoryImpl: QSFactory,
        private val qsPipelineFlagsRepository: QSPipelineFlagsRepository,
){
    /**
     * Checks a list of tiles and returns which are unavailable. If there's no availability
     * interactor (or the new tiles flag is off), it will construct an instance of the tile to check
     * its availability and destroy it. Because of this it's recommended to call this method
     * sparingly.
     */
    suspend fun getUnavailableTiles(platformTilesToCheck: Iterable<TileSpec>): Set<TileSpec> {
        check(platformTilesToCheck.all { it is TileSpec.PlatformTileSpec })
        val newTilesAvailability = if (qsPipelineFlagsRepository.tilesEnabled) {
            newTilesAvailabilityInteractor.newTilesAvailable.first()
        } else {
            emptyMap()
        }
        return (platformTilesToCheck.minus(newTilesAvailability.keys).filterNot {
            val tile = qsFactoryImpl.createTile(it.spec)
            (tile?.isAvailable ?: false).also {
                tile?.destroy()
            }
        } + newTilesAvailability.filterNot { it.value }.keys).toSet()
    }
}

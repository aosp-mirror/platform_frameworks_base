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

import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Interactor to resize QS tiles down to icons when removed from the current tiles. */
class DynamicIconTilesInteractor
@AssistedInject
constructor(
    private val iconTilesInteractor: IconTilesInteractor,
    private val currentTilesInteractor: CurrentTilesInteractor,
) : ExclusiveActivatable() {

    override suspend fun onActivated(): Nothing {
        currentTilesInteractor.currentTiles.collect { currentTiles ->
            // Only current tiles can be resized, so observe the current tiles and find the
            // intersection between them and the large tiles.
            val newLargeTiles =
                iconTilesInteractor.largeTilesSpecs.value intersect
                    currentTiles.map { it.spec }.toSet()
            iconTilesInteractor.setLargeTiles(newLargeTiles)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): DynamicIconTilesInteractor
    }
}

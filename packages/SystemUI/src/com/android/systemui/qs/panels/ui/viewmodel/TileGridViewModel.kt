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

package com.android.systemui.qs.panels.ui.viewmodel

import androidx.compose.runtime.getValue
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.qs.panels.domain.interactor.GridLayoutTypeInteractor
import com.android.systemui.qs.panels.shared.model.GridLayoutType
import com.android.systemui.qs.panels.ui.compose.GridLayout
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Named
import kotlinx.coroutines.flow.map

class TileGridViewModel
@AssistedInject
constructor(
    gridLayoutTypeInteractor: GridLayoutTypeInteractor,
    gridLayoutMap: Map<GridLayoutType, @JvmSuppressWildcards GridLayout>,
    tilesInteractor: CurrentTilesInteractor,
    @Named("Default") defaultGridLayout: GridLayout,
) : ExclusiveActivatable() {

    private val hydrator = Hydrator("TileGridViewModel")

    val gridLayout by
        hydrator.hydratedStateOf(
            traceName = "gridLayout",
            source = gridLayoutTypeInteractor.layout.map { gridLayoutMap[it] ?: defaultGridLayout },
            initialValue = defaultGridLayout,
        )

    private val tileModels by
        hydrator.hydratedStateOf(traceName = "tileModels", source = tilesInteractor.currentTiles)

    val tileViewModels: List<TileViewModel>
        get() = tileModels.map { TileViewModel(it.tile, it.spec) }

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    @AssistedFactory
    interface Factory {
        fun create(): TileGridViewModel
    }
}

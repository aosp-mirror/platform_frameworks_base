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

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModelFactoryProvider
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.qs.panels.domain.interactor.QuickQuickSettingsRowInteractor
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.shared.model.splitInRowsSequence
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class QuickQuickSettingsViewModel
@AssistedInject
constructor(
    tilesInteractor: CurrentTilesInteractor,
    private val qsColumnsViewModel: QSColumnsViewModel,
    quickQuickSettingsRowInteractor: QuickQuickSettingsRowInteractor,
    val squishinessViewModel: TileSquishinessViewModel,
    iconTilesViewModel: IconTilesViewModel,
    val tileHapticsViewModelFactoryProvider: TileHapticsViewModelFactoryProvider,
) : ExclusiveActivatable() {

    private val hydrator = Hydrator("QuickQuickSettingsViewModel")

    val columns by qsColumnsViewModel.columns

    private val largeTiles by
        hydrator.hydratedStateOf(traceName = "largeTiles", source = iconTilesViewModel.largeTiles)

    private val rows by
        hydrator.hydratedStateOf(
            traceName = "rows",
            initialValue = quickQuickSettingsRowInteractor.defaultRows,
            source = quickQuickSettingsRowInteractor.rows,
        )

    private val currentTiles by
        hydrator.hydratedStateOf(traceName = "currentTiles", source = tilesInteractor.currentTiles)

    val tileViewModels by derivedStateOf {
        currentTiles
            .map { SizedTileImpl(TileViewModel(it.tile, it.spec), it.spec.width) }
            .let { splitInRowsSequence(it, columns).take(rows).toList().flatten() }
    }

    private val TileSpec.width: Int
        get() = if (largeTiles.contains(this)) 2 else 1

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch { hydrator.activate() }
            launch { qsColumnsViewModel.activate() }
            awaitCancellation()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): QuickQuickSettingsViewModel
    }
}

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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.qs.panels.domain.interactor.QuickQuickSettingsRowInteractor
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.shared.model.splitInRowsSequence
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class QuickQuickSettingsViewModel
@Inject
constructor(
    tilesInteractor: CurrentTilesInteractor,
    fixedColumnsSizeViewModel: FixedColumnsSizeViewModel,
    quickQuickSettingsRowInteractor: QuickQuickSettingsRowInteractor,
    private val iconTilesViewModel: IconTilesViewModel,
    @Application private val applicationScope: CoroutineScope,
) {

    val columns = fixedColumnsSizeViewModel.columns

    private val rows =
        quickQuickSettingsRowInteractor.rows.stateIn(
            applicationScope,
            SharingStarted.WhileSubscribed(),
            quickQuickSettingsRowInteractor.defaultRows
        )

    val tileViewModels: StateFlow<List<SizedTile<TileViewModel>>> =
        columns
            .flatMapLatest { columns ->
                tilesInteractor.currentTiles.combine(rows, ::Pair).mapLatest { (tiles, rows) ->
                    tiles
                        .map {
                            SizedTileImpl(
                                TileViewModel(it.tile, it.spec),
                                it.spec.width,
                            )
                        }
                        .let { splitInRowsSequence(it, columns).take(rows).toList().flatten() }
                }
            }
            .stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(),
                tilesInteractor.currentTiles.value
                    .map {
                        SizedTileImpl(
                            TileViewModel(it.tile, it.spec),
                            it.spec.width,
                        )
                    }
                    .let {
                        splitInRowsSequence(it, columns.value).take(rows.value).toList().flatten()
                    }
            )

    private val TileSpec.width: Int
        get() = if (iconTilesViewModel.isIconTile(this)) 1 else 2
}

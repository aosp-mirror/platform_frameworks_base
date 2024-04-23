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
import com.android.systemui.qs.panels.domain.interactor.GridLayoutTypeInteractor
import com.android.systemui.qs.panels.domain.interactor.IconTilesInteractor
import com.android.systemui.qs.panels.shared.model.GridLayoutType
import com.android.systemui.qs.panels.ui.compose.GridLayout
import com.android.systemui.qs.panels.ui.compose.InfiniteGridLayout
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@SysUISingleton
class TileGridViewModel
@Inject
constructor(
    gridLayoutTypeInteractor: GridLayoutTypeInteractor,
    gridLayoutMap: Map<Class<out GridLayoutType>, @JvmSuppressWildcards GridLayout>,
    tilesInteractor: CurrentTilesInteractor,
    iconTilesInteractor: IconTilesInteractor,
) {
    val gridLayout: Flow<GridLayout> =
        gridLayoutTypeInteractor.layout.map {
            gridLayoutMap[it::class.java] ?: InfiniteGridLayout()
        }
    val tileViewModels: Flow<List<TileViewModel>> =
        combine(tilesInteractor.currentTiles, iconTilesInteractor.iconTilesSpecs) {
            tiles,
            iconTilesSpecs ->
            tiles.map { TileViewModel(it.tile, iconTilesSpecs.contains(it.spec)) }
        }
}

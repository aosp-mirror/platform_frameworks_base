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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.res.R
import javax.inject.Inject

class InfiniteGridLayout @Inject constructor() : GridLayout {

    @Composable
    override fun TileGrid(
        tiles: List<TileViewModel>,
        modifier: Modifier,
        tile: @Composable (TileViewModel) -> Unit
    ) {
        DisposableEffect(tiles) {
            val token = Any()
            tiles.forEach { it.startListening(token) }
            onDispose { tiles.forEach { it.stopListening(token) } }
        }

        LazyVerticalGrid(
            columns =
                GridCells.Fixed(
                    integerResource(R.integer.quick_settings_infinite_grid_num_columns)
                ),
            verticalArrangement =
                Arrangement.spacedBy(dimensionResource(R.dimen.qs_tile_margin_vertical)),
            horizontalArrangement =
                Arrangement.spacedBy(dimensionResource(R.dimen.qs_tile_margin_horizontal)),
            modifier = modifier
        ) {
            tiles.forEach { item(span = { it.span() }) { tile(it) } }
        }
    }

    private fun TileViewModel.span(): GridItemSpan =
        if (iconOnly) {
            GridItemSpan(1)
        } else {
            GridItemSpan(2)
        }
}

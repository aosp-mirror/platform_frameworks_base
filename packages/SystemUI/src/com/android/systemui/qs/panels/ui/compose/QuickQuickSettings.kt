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

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.panels.ui.viewmodel.QuickQuickSettingsViewModel

@Composable
fun QuickQuickSettings(
    viewModel: QuickQuickSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val sizedTiles by
        viewModel.tileViewModels.collectAsStateWithLifecycle(initialValue = emptyList())
    val tiles = sizedTiles.map { it.tile }

    DisposableEffect(tiles) {
        val token = Any()
        tiles.forEach { it.startListening(token) }
        onDispose { tiles.forEach { it.stopListening(token) } }
    }
    val columns by viewModel.columns.collectAsStateWithLifecycle()

    TileLazyGrid(
        modifier = modifier.sysuiResTag("qqs_tile_layout"),
        columns = GridCells.Fixed(columns)
    ) {
        items(
            tiles.size,
            key = { index -> sizedTiles[index].tile.spec.spec },
            span = { index -> GridItemSpan(sizedTiles[index].width) }
        ) { index ->
            Tile(tile = tiles[index], iconOnly = sizedTiles[index].isIcon, modifier = Modifier)
        }
    }
}

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

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.grid.ui.compose.VerticalSpannedGrid
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.panels.ui.compose.infinitegrid.Tile
import com.android.systemui.qs.panels.ui.viewmodel.QuickQuickSettingsViewModel
import com.android.systemui.qs.shared.ui.ElementKeys.toElementKey
import com.android.systemui.res.R

@Composable
fun SceneScope.QuickQuickSettings(
    viewModel: QuickQuickSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val sizedTiles by
        viewModel.tileViewModels.collectAsStateWithLifecycle(initialValue = emptyList())
    val tiles = sizedTiles.fastMap { it.tile }

    DisposableEffect(tiles) {
        val token = Any()
        tiles.forEach { it.startListening(token) }
        onDispose { tiles.forEach { it.stopListening(token) } }
    }
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    Box(modifier = modifier) {
        GridAnchor()
        VerticalSpannedGrid(
            columns = columns,
            columnSpacing = dimensionResource(R.dimen.qs_tile_margin_horizontal),
            rowSpacing = dimensionResource(R.dimen.qs_tile_margin_vertical),
            spans = sizedTiles.fastMap { it.width },
            modifier = Modifier.sysuiResTag("qqs_tile_layout"),
        ) { spanIndex ->
            val it = sizedTiles[spanIndex]
            Tile(
                tile = it.tile,
                iconOnly = it.isIcon,
                modifier = Modifier.element(it.tile.spec.toElementKey(spanIndex)),
            )
        }
    }
}

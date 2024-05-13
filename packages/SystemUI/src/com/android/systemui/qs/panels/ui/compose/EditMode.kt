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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel

@Composable
fun EditMode(
    viewModel: EditModeViewModel,
    modifier: Modifier = Modifier,
) {
    val gridLayout by viewModel.gridLayout.collectAsStateWithLifecycle()
    val tiles by viewModel.tiles.collectAsStateWithLifecycle(emptyList())

    BackHandler { viewModel.stopEditing() }

    DisposableEffect(Unit) { onDispose { viewModel.stopEditing() } }

    Column(modifier) {
        gridLayout.EditTileGrid(
            tiles,
            Modifier,
            viewModel::addTile,
            viewModel::removeTile,
        )
    }
}

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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.qs.panels.ui.viewmodel.TileGridViewModel

@Composable
fun SceneScope.TileGrid(
    viewModel: TileGridViewModel,
    modifier: Modifier = Modifier,
    editModeStart: () -> Unit,
) {
    val gridLayout by viewModel.gridLayout.collectAsStateWithLifecycle()
    val tiles by viewModel.tileViewModels.collectAsStateWithLifecycle(emptyList())

    with(gridLayout) { TileGrid(tiles, modifier, editModeStart) }
}

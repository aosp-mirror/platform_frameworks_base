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

import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.qs.pipeline.shared.TileSpec

/**
 * View model for each tile that is available to be added/removed/moved in Edit mode.
 *
 * [isCurrent] indicates whether this tile is part of the current set of tiles that the user sees in
 * Quick Settings.
 */
class EditTileViewModel(
    val tileSpec: TileSpec,
    val icon: Icon,
    val label: Text,
    val appName: Text?,
    val isCurrent: Boolean,
    val availableEditActions: Set<AvailableEditActions>,
)

enum class AvailableEditActions {
    ADD,
    REMOVE,
    MOVE,
}

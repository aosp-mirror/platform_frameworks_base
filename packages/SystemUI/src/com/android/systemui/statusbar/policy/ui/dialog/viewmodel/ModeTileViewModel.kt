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

package com.android.systemui.statusbar.policy.ui.dialog.viewmodel

import com.android.systemui.common.shared.model.Icon

/**
 * Viewmodel for a tile representing a single priority ("zen") mode, for use within the modes
 * dialog. Not to be confused with ModesTile, which is the Quick Settings tile that opens the
 * dialog.
 */
data class ModeTileViewModel(
    val id: String,
    val icon: Icon,
    val text: String,
    val subtext: String,
    val subtextDescription: String, // version of subtext without "on"/"off" for screen readers
    val enabled: Boolean,
    val stateDescription: String, // "on"/"off" state of the tile, for screen readers
    val onClick: () -> Unit,
    val onLongClick: () -> Unit,
    val onLongClickLabel: String, // for screen readers
)

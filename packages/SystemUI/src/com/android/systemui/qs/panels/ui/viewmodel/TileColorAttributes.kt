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

import android.service.quicksettings.Tile
import androidx.annotation.AttrRes
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.res.R

data class TileColorAttributes(
    @AttrRes val background: Int = 0,
    @AttrRes val label: Int = 0,
    @AttrRes val secondaryLabel: Int = 0,
    @AttrRes val icon: Int = 0,
)

val ActiveTileColorAttributes =
    TileColorAttributes(
        background = R.attr.shadeActive,
        label = R.attr.onShadeActive,
        secondaryLabel = R.attr.onShadeActiveVariant,
        icon = R.attr.onShadeActive,
    )

val InactiveTileColorAttributes =
    TileColorAttributes(
        background = R.attr.shadeInactive,
        label = R.attr.onShadeInactive,
        secondaryLabel = R.attr.onShadeInactiveVariant,
        icon = R.attr.onShadeInactiveVariant,
    )

val UnavailableTileColorAttributes =
    TileColorAttributes(
        background = R.attr.shadeDisabled,
        label = R.attr.outline,
        secondaryLabel = R.attr.outline,
        icon = R.attr.outline,
    )

fun QSTile.State.colors(): TileColorAttributes =
    when (state) {
        Tile.STATE_UNAVAILABLE -> UnavailableTileColorAttributes
        Tile.STATE_ACTIVE -> ActiveTileColorAttributes
        Tile.STATE_INACTIVE -> InactiveTileColorAttributes
        else -> TileColorAttributes()
    }

/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.custom.commons

import android.service.quicksettings.Tile

fun Tile.copy(): Tile =
    Tile().also {
        it.icon = icon
        it.label = label
        it.subtitle = subtitle
        it.contentDescription = contentDescription
        it.stateDescription = stateDescription
        it.activityLaunchForClick = activityLaunchForClick
        it.state = state
    }

fun Tile.setFrom(otherTile: Tile) {
    if (otherTile.icon != null) {
        icon = otherTile.icon
    }
    if (otherTile.customLabel != null) {
        label = otherTile.customLabel
    }
    if (otherTile.subtitle != null) {
        subtitle = otherTile.subtitle
    }
    if (otherTile.contentDescription != null) {
        contentDescription = otherTile.contentDescription
    }
    if (otherTile.stateDescription != null) {
        stateDescription = otherTile.stateDescription
    }
    activityLaunchForClick = otherTile.activityLaunchForClick
    state = otherTile.state
}

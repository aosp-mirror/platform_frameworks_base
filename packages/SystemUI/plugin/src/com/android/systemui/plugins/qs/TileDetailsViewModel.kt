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

package com.android.systemui.plugins.qs

import androidx.compose.runtime.Composable

/**
 * The base view model class for rendering the Tile's TileDetailsView.
 */
abstract class TileDetailsViewModel {

    // The view content of this tile details view.
    @Composable
    abstract fun GetContentView()

    // The callback when the settings button is clicked. Currently this is the same as the on tile
    // long press callback
    abstract fun clickOnSettingsButton()

    abstract fun getTitle(): String

    abstract fun getSubTitle(): String
}

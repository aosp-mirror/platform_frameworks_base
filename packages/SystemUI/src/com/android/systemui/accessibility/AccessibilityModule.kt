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

package com.android.systemui.accessibility

import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.ColorCorrectionTile
import com.android.systemui.qs.tiles.ColorInversionTile
import com.android.systemui.qs.tiles.DreamTile
import com.android.systemui.qs.tiles.FontScalingTile
import com.android.systemui.qs.tiles.NightDisplayTile
import com.android.systemui.qs.tiles.OneHandedModeTile
import com.android.systemui.qs.tiles.ReduceBrightColorsTile
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface AccessibilityModule {

    /** Inject ColorInversionTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(ColorInversionTile.TILE_SPEC)
    fun bindColorInversionTile(colorInversionTile: ColorInversionTile): QSTileImpl<*>

    /** Inject NightDisplayTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(NightDisplayTile.TILE_SPEC)
    fun bindNightDisplayTile(nightDisplayTile: NightDisplayTile): QSTileImpl<*>

    /** Inject ReduceBrightColorsTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(ReduceBrightColorsTile.TILE_SPEC)
    fun bindReduceBrightColorsTile(reduceBrightColorsTile: ReduceBrightColorsTile): QSTileImpl<*>

    /** Inject OneHandedModeTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(OneHandedModeTile.TILE_SPEC)
    fun bindOneHandedModeTile(oneHandedModeTile: OneHandedModeTile): QSTileImpl<*>

    /** Inject ColorCorrectionTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(ColorCorrectionTile.TILE_SPEC)
    fun bindColorCorrectionTile(colorCorrectionTile: ColorCorrectionTile): QSTileImpl<*>

    /** Inject DreamTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(DreamTile.TILE_SPEC)
    fun bindDreamTile(dreamTile: DreamTile): QSTileImpl<*>

    /** Inject FontScalingTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(FontScalingTile.TILE_SPEC)
    fun bindFontScalingTile(fontScalingTile: FontScalingTile): QSTileImpl<*>
}

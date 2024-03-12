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

package com.android.systemui.accessibility.qs

import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.ColorCorrectionTile
import com.android.systemui.qs.tiles.ColorInversionTile
import com.android.systemui.qs.tiles.DreamTile
import com.android.systemui.qs.tiles.FontScalingTile
import com.android.systemui.qs.tiles.NightDisplayTile
import com.android.systemui.qs.tiles.OneHandedModeTile
import com.android.systemui.qs.tiles.ReduceBrightColorsTile
import com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelFactory
import com.android.systemui.qs.tiles.impl.colorcorrection.domain.ColorCorrectionTileMapper
import com.android.systemui.qs.tiles.impl.colorcorrection.domain.interactor.ColorCorrectionTileDataInteractor
import com.android.systemui.qs.tiles.impl.colorcorrection.domain.interactor.ColorCorrectionUserActionInteractor
import com.android.systemui.qs.tiles.impl.colorcorrection.domain.model.ColorCorrectionTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileUIConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface QSAccessibilityModule {

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

    companion object {

        const val COLOR_CORRECTION_TILE_SPEC = "color_correction"

        @Provides
        @IntoMap
        @StringKey(COLOR_CORRECTION_TILE_SPEC)
        fun provideColorCorrectionTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(COLOR_CORRECTION_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_color_correction,
                        labelRes = R.string.quick_settings_color_correction_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
            )

        /** Inject ColorCorrectionTile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(COLOR_CORRECTION_TILE_SPEC)
        fun provideColorCorrectionTileViewModel(
            factory: QSTileViewModelFactory.Static<ColorCorrectionTileModel>,
            mapper: ColorCorrectionTileMapper,
            stateInteractor: ColorCorrectionTileDataInteractor,
            userActionInteractor: ColorCorrectionUserActionInteractor
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(COLOR_CORRECTION_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )
    }
}

/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.screenrecord

import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.ScreenRecordTile
import com.android.systemui.qs.tiles.base.interactor.QSTileAvailabilityInteractor
import com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelFactory
import com.android.systemui.qs.tiles.impl.screenrecord.domain.interactor.ScreenRecordTileDataInteractor
import com.android.systemui.qs.tiles.impl.screenrecord.domain.interactor.ScreenRecordTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.screenrecord.domain.ui.ScreenRecordTileMapper
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileUIConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.ScreenRecordRepository
import com.android.systemui.screenrecord.data.repository.ScreenRecordRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface ScreenRecordModule {

    @Binds fun bindScreenRecordRepository(impl: ScreenRecordRepositoryImpl): ScreenRecordRepository

    /** Inject ScreenRecordTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(ScreenRecordTile.TILE_SPEC)
    fun bindScreenRecordTile(screenRecordTile: ScreenRecordTile): QSTileImpl<*>

    @Binds
    @IntoMap
    @StringKey(SCREEN_RECORD_TILE_SPEC)
    fun provideScreenRecordAvailabilityInteractor(
            impl: ScreenRecordTileDataInteractor
    ): QSTileAvailabilityInteractor

    companion object {
        private const val SCREEN_RECORD_TILE_SPEC = "screenrecord"

        @Provides
        @IntoMap
        @StringKey(SCREEN_RECORD_TILE_SPEC)
        fun provideScreenRecordTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(SCREEN_RECORD_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.qs_screen_record_icon_off,
                        labelRes = R.string.quick_settings_screen_record_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
            )

        /** Inject ScreenRecord Tile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(SCREEN_RECORD_TILE_SPEC)
        fun provideScreenRecordTileViewModel(
            factory: QSTileViewModelFactory.Static<ScreenRecordModel>,
            mapper: ScreenRecordTileMapper,
            stateInteractor: ScreenRecordTileDataInteractor,
            userActionInteractor: ScreenRecordTileUserActionInteractor
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(SCREEN_RECORD_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )
    }
}

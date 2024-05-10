/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use mHost file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy

import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.AlarmTile
import com.android.systemui.qs.tiles.CameraToggleTile
import com.android.systemui.qs.tiles.DndTile
import com.android.systemui.qs.tiles.FlashlightTile
import com.android.systemui.qs.tiles.LocationTile
import com.android.systemui.qs.tiles.MicrophoneToggleTile
import com.android.systemui.qs.tiles.UiModeNightTile
import com.android.systemui.qs.tiles.WorkModeTile
import com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelFactory
import com.android.systemui.qs.tiles.impl.alarm.domain.AlarmTileMapper
import com.android.systemui.qs.tiles.impl.alarm.domain.interactor.AlarmTileDataInteractor
import com.android.systemui.qs.tiles.impl.alarm.domain.interactor.AlarmTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.alarm.domain.model.AlarmTileModel
import com.android.systemui.qs.tiles.impl.flashlight.domain.FlashlightMapper
import com.android.systemui.qs.tiles.impl.flashlight.domain.interactor.FlashlightTileDataInteractor
import com.android.systemui.qs.tiles.impl.flashlight.domain.interactor.FlashlightTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.flashlight.domain.model.FlashlightTileModel
import com.android.systemui.qs.tiles.impl.location.domain.LocationTileMapper
import com.android.systemui.qs.tiles.impl.location.domain.interactor.LocationTileDataInteractor
import com.android.systemui.qs.tiles.impl.location.domain.interactor.LocationTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.location.domain.model.LocationTileModel
import com.android.systemui.qs.tiles.impl.uimodenight.domain.UiModeNightTileMapper
import com.android.systemui.qs.tiles.impl.uimodenight.domain.interactor.UiModeNightTileDataInteractor
import com.android.systemui.qs.tiles.impl.uimodenight.domain.interactor.UiModeNightTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.uimodenight.domain.model.UiModeNightTileModel
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
interface PolicyModule {

    /** Inject DndTile into tileMap in QSModule */
    @Binds @IntoMap @StringKey(DndTile.TILE_SPEC) fun bindDndTile(dndTile: DndTile): QSTileImpl<*>

    /** Inject WorkModeTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(WorkModeTile.TILE_SPEC)
    fun bindWorkModeTile(workModeTile: WorkModeTile): QSTileImpl<*>

    companion object {
        const val FLASHLIGHT_TILE_SPEC = "flashlight"
        const val LOCATION_TILE_SPEC = "location"
        const val ALARM_TILE_SPEC = "alarm"
        const val UIMODENIGHT_TILE_SPEC = "dark"

        /** Inject flashlight config */
        @Provides
        @IntoMap
        @StringKey(FLASHLIGHT_TILE_SPEC)
        fun provideFlashlightTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(FLASHLIGHT_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.qs_flashlight_icon_off,
                        labelRes = R.string.quick_settings_flashlight_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
            )

        /** Inject FlashlightTile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(FLASHLIGHT_TILE_SPEC)
        fun provideFlashlightTileViewModel(
            factory: QSTileViewModelFactory.Static<FlashlightTileModel>,
            mapper: FlashlightMapper,
            stateInteractor: FlashlightTileDataInteractor,
            userActionInteractor: FlashlightTileUserActionInteractor
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(FLASHLIGHT_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )

        /** Inject location config */
        @Provides
        @IntoMap
        @StringKey(LOCATION_TILE_SPEC)
        fun provideLocationTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(LOCATION_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.qs_location_icon_off,
                        labelRes = R.string.quick_settings_location_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
            )

        /** Inject LocationTile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(LOCATION_TILE_SPEC)
        fun provideLocationTileViewModel(
            factory: QSTileViewModelFactory.Static<LocationTileModel>,
            mapper: LocationTileMapper,
            stateInteractor: LocationTileDataInteractor,
            userActionInteractor: LocationTileUserActionInteractor
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(LOCATION_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )

        /** Inject alarm config */
        @Provides
        @IntoMap
        @StringKey(ALARM_TILE_SPEC)
        fun provideAlarmTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(ALARM_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_alarm,
                        labelRes = R.string.status_bar_alarm,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
            )

        /** Inject AlarmTile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(ALARM_TILE_SPEC)
        fun provideAlarmTileViewModel(
            factory: QSTileViewModelFactory.Static<AlarmTileModel>,
            mapper: AlarmTileMapper,
            stateInteractor: AlarmTileDataInteractor,
            userActionInteractor: AlarmTileUserActionInteractor
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(ALARM_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )

        /** Inject uimodenight config */
        @Provides
        @IntoMap
        @StringKey(UIMODENIGHT_TILE_SPEC)
        fun provideUiModeNightTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(UIMODENIGHT_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.qs_light_dark_theme_icon_off,
                        labelRes = R.string.quick_settings_ui_mode_night_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
            )

        /** Inject uimodenight into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(UIMODENIGHT_TILE_SPEC)
        fun provideUiModeNightTileViewModel(
            factory: QSTileViewModelFactory.Static<UiModeNightTileModel>,
            mapper: UiModeNightTileMapper,
            stateInteractor: UiModeNightTileDataInteractor,
            userActionInteractor: UiModeNightTileUserActionInteractor
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(UIMODENIGHT_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )
    }

    /** Inject FlashlightTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(FlashlightTile.TILE_SPEC)
    fun bindFlashlightTile(flashlightTile: FlashlightTile): QSTileImpl<*>

    /** Inject LocationTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(LocationTile.TILE_SPEC)
    fun bindLocationTile(locationTile: LocationTile): QSTileImpl<*>

    /** Inject CameraToggleTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(CameraToggleTile.TILE_SPEC)
    fun bindCameraToggleTile(cameraToggleTile: CameraToggleTile): QSTileImpl<*>

    /** Inject MicrophoneToggleTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(MicrophoneToggleTile.TILE_SPEC)
    fun bindMicrophoneToggleTile(microphoneToggleTile: MicrophoneToggleTile): QSTileImpl<*>

    /** Inject AlarmTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(AlarmTile.TILE_SPEC)
    fun bindAlarmTile(alarmTile: AlarmTile): QSTileImpl<*>

    @Binds
    @IntoMap
    @StringKey(UiModeNightTile.TILE_SPEC)
    fun bindUiModeNightTile(uiModeNightTile: UiModeNightTile): QSTileImpl<*>
}

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

import android.hardware.SensorPrivacyManager.Sensors.CAMERA
import android.hardware.SensorPrivacyManager.Sensors.MICROPHONE
import android.os.UserManager.DISALLOW_CAMERA_TOGGLE
import android.os.UserManager.DISALLOW_CONFIG_LOCATION
import android.os.UserManager.DISALLOW_MICROPHONE_TOGGLE
import android.os.UserManager.DISALLOW_SHARE_LOCATION
import com.android.systemui.Flags
import com.android.systemui.modes.shared.ModesUi
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.AlarmTile
import com.android.systemui.qs.tiles.CameraToggleTile
import com.android.systemui.qs.tiles.DndTile
import com.android.systemui.qs.tiles.FlashlightTile
import com.android.systemui.qs.tiles.LocationTile
import com.android.systemui.qs.tiles.MicrophoneToggleTile
import com.android.systemui.qs.tiles.ModesTile
import com.android.systemui.qs.tiles.UiModeNightTile
import com.android.systemui.qs.tiles.WorkModeTile
import com.android.systemui.qs.tiles.base.interactor.QSTileAvailabilityInteractor
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
import com.android.systemui.qs.tiles.impl.modes.domain.interactor.ModesTileDataInteractor
import com.android.systemui.qs.tiles.impl.modes.domain.interactor.ModesTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import com.android.systemui.qs.tiles.impl.modes.ui.ModesTileMapper
import com.android.systemui.qs.tiles.impl.sensorprivacy.SensorPrivacyToggleTileDataInteractor
import com.android.systemui.qs.tiles.impl.sensorprivacy.domain.SensorPrivacyToggleTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.sensorprivacy.domain.model.SensorPrivacyToggleTileModel
import com.android.systemui.qs.tiles.impl.sensorprivacy.ui.SensorPrivacyTileResources
import com.android.systemui.qs.tiles.impl.sensorprivacy.ui.SensorPrivacyToggleTileMapper
import com.android.systemui.qs.tiles.impl.uimodenight.domain.UiModeNightTileMapper
import com.android.systemui.qs.tiles.impl.uimodenight.domain.interactor.UiModeNightTileDataInteractor
import com.android.systemui.qs.tiles.impl.uimodenight.domain.interactor.UiModeNightTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.uimodenight.domain.model.UiModeNightTileModel
import com.android.systemui.qs.tiles.impl.work.domain.interactor.WorkModeTileDataInteractor
import com.android.systemui.qs.tiles.impl.work.domain.interactor.WorkModeTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.work.domain.model.WorkModeTileModel
import com.android.systemui.qs.tiles.impl.work.ui.WorkModeTileMapper
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTilePolicy
import com.android.systemui.qs.tiles.viewmodel.QSTileUIConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.qs.tiles.viewmodel.StubQSTileViewModel
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Provider

@Module
interface PolicyModule {

    /** Inject WorkModeTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(WorkModeTile.TILE_SPEC)
    fun bindWorkModeTile(workModeTile: WorkModeTile): QSTileImpl<*>

    @Binds
    @IntoMap
    @StringKey(FLASHLIGHT_TILE_SPEC)
    fun provideAirplaneModeAvailabilityInteractor(
        impl: FlashlightTileDataInteractor
    ): QSTileAvailabilityInteractor

    @Binds
    @IntoMap
    @StringKey(LOCATION_TILE_SPEC)
    fun provideLocationAvailabilityInteractor(
        impl: LocationTileDataInteractor
    ): QSTileAvailabilityInteractor

    @Binds
    @IntoMap
    @StringKey(ALARM_TILE_SPEC)
    fun provideAlarmAvailabilityInteractor(
        impl: AlarmTileDataInteractor
    ): QSTileAvailabilityInteractor

    @Binds
    @IntoMap
    @StringKey(UIMODENIGHT_TILE_SPEC)
    fun provideUiModeNightAvailabilityInteractor(
        impl: UiModeNightTileDataInteractor
    ): QSTileAvailabilityInteractor

    @Binds
    @IntoMap
    @StringKey(WORK_MODE_TILE_SPEC)
    fun provideWorkModeAvailabilityInteractor(
        impl: WorkModeTileDataInteractor
    ): QSTileAvailabilityInteractor

    companion object {
        const val FLASHLIGHT_TILE_SPEC = "flashlight"
        const val LOCATION_TILE_SPEC = "location"
        const val ALARM_TILE_SPEC = "alarm"
        const val UIMODENIGHT_TILE_SPEC = "dark"
        const val WORK_MODE_TILE_SPEC = "work"
        const val CAMERA_TOGGLE_TILE_SPEC = "cameratoggle"
        const val MIC_TOGGLE_TILE_SPEC = "mictoggle"
        const val DND_TILE_SPEC = "dnd"

        /** Inject DndTile or ModesTile into tileMap in QSModule based on feature flag */
        @Provides
        @IntoMap
        @StringKey(DND_TILE_SPEC)
        fun bindDndOrModesTile(
            // Using providers to make sure that the unused tile isn't initialised at all if the
            // flag is off.
            dndTile: Provider<DndTile>,
            modesTile: Provider<ModesTile>,
        ): QSTileImpl<*> {
            return if (ModesUi.isEnabled) modesTile.get() else dndTile.get()
        }

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
                category = TileCategory.UTILITIES,
            )

        /** Inject FlashlightTile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(FLASHLIGHT_TILE_SPEC)
        fun provideFlashlightTileViewModel(
            factory: QSTileViewModelFactory.Static<FlashlightTileModel>,
            mapper: FlashlightMapper,
            stateInteractor: FlashlightTileDataInteractor,
            userActionInteractor: FlashlightTileUserActionInteractor,
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
                policy =
                    QSTilePolicy.Restricted(
                        listOf(DISALLOW_SHARE_LOCATION, DISALLOW_CONFIG_LOCATION)
                    ),
                category = TileCategory.PRIVACY,
            )

        /** Inject LocationTile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(LOCATION_TILE_SPEC)
        fun provideLocationTileViewModel(
            factory: QSTileViewModelFactory.Static<LocationTileModel>,
            mapper: LocationTileMapper,
            stateInteractor: LocationTileDataInteractor,
            userActionInteractor: LocationTileUserActionInteractor,
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
                category = TileCategory.UTILITIES,
            )

        /** Inject AlarmTile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(ALARM_TILE_SPEC)
        fun provideAlarmTileViewModel(
            factory: QSTileViewModelFactory.Static<AlarmTileModel>,
            mapper: AlarmTileMapper,
            stateInteractor: AlarmTileDataInteractor,
            userActionInteractor: AlarmTileUserActionInteractor,
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
                category = TileCategory.DISPLAY,
            )

        /** Inject uimodenight into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(UIMODENIGHT_TILE_SPEC)
        fun provideUiModeNightTileViewModel(
            factory: QSTileViewModelFactory.Static<UiModeNightTileModel>,
            mapper: UiModeNightTileMapper,
            stateInteractor: UiModeNightTileDataInteractor,
            userActionInteractor: UiModeNightTileUserActionInteractor,
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(UIMODENIGHT_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )

        /** Inject work mode tile config */
        @Provides
        @IntoMap
        @StringKey(WORK_MODE_TILE_SPEC)
        fun provideWorkModeTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(WORK_MODE_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = com.android.internal.R.drawable.stat_sys_managed_profile_status,
                        labelRes = R.string.quick_settings_work_mode_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                autoRemoveOnUnavailable = false,
                category = TileCategory.PRIVACY,
            )

        /** Inject work mode into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(WORK_MODE_TILE_SPEC)
        fun provideWorkModeTileViewModel(
            factory: QSTileViewModelFactory.Static<WorkModeTileModel>,
            mapper: WorkModeTileMapper,
            stateInteractor: WorkModeTileDataInteractor,
            userActionInteractor: WorkModeTileUserActionInteractor,
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(WORK_MODE_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )

        /** Inject camera toggle config */
        @Provides
        @IntoMap
        @StringKey(CAMERA_TOGGLE_TILE_SPEC)
        fun provideCameraToggleTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(CAMERA_TOGGLE_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.qs_camera_access_icon_off,
                        labelRes = R.string.quick_settings_camera_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                policy = QSTilePolicy.Restricted(listOf(DISALLOW_CAMERA_TOGGLE)),
                category = TileCategory.PRIVACY,
            )

        /** Inject camera toggle tile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(CAMERA_TOGGLE_TILE_SPEC)
        fun provideCameraToggleTileViewModel(
            factory: QSTileViewModelFactory.Static<SensorPrivacyToggleTileModel>,
            mapper: SensorPrivacyToggleTileMapper.Factory,
            stateInteractor: SensorPrivacyToggleTileDataInteractor.Factory,
            userActionInteractor: SensorPrivacyToggleTileUserActionInteractor.Factory,
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(CAMERA_TOGGLE_TILE_SPEC),
                userActionInteractor.create(CAMERA),
                stateInteractor.create(CAMERA),
                mapper.create(SensorPrivacyTileResources.CameraPrivacyTileResources),
            )

        @Provides
        @IntoMap
        @StringKey(CAMERA_TOGGLE_TILE_SPEC)
        fun provideCameraToggleAvailabilityInteractor(
            factory: SensorPrivacyToggleTileDataInteractor.Factory
        ): QSTileAvailabilityInteractor {
            return factory.create(CAMERA)
        }

        /** Inject microphone toggle config */
        @Provides
        @IntoMap
        @StringKey(MIC_TOGGLE_TILE_SPEC)
        fun provideMicrophoneToggleTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(MIC_TOGGLE_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.qs_mic_access_off,
                        labelRes = R.string.quick_settings_mic_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                policy = QSTilePolicy.Restricted(listOf(DISALLOW_MICROPHONE_TOGGLE)),
                category = TileCategory.PRIVACY,
            )

        /** Inject microphone toggle tile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(MIC_TOGGLE_TILE_SPEC)
        fun provideMicrophoneToggleTileViewModel(
            factory: QSTileViewModelFactory.Static<SensorPrivacyToggleTileModel>,
            mapper: SensorPrivacyToggleTileMapper.Factory,
            stateInteractor: SensorPrivacyToggleTileDataInteractor.Factory,
            userActionInteractor: SensorPrivacyToggleTileUserActionInteractor.Factory,
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(MIC_TOGGLE_TILE_SPEC),
                userActionInteractor.create(MICROPHONE),
                stateInteractor.create(MICROPHONE),
                mapper.create(SensorPrivacyTileResources.MicrophonePrivacyTileResources),
            )

        @Provides
        @IntoMap
        @StringKey(MIC_TOGGLE_TILE_SPEC)
        fun provideMicToggleModeAvailabilityInteractor(
            factory: SensorPrivacyToggleTileDataInteractor.Factory
        ): QSTileAvailabilityInteractor {
            return factory.create(MICROPHONE)
        }

        /** Inject DND tile or Modes tile config based on feature flag */
        @Provides
        @IntoMap
        @StringKey(DND_TILE_SPEC)
        fun provideDndOrModesTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            if (ModesUi.isEnabled) {
                QSTileConfig(
                    tileSpec = TileSpec.create(DND_TILE_SPEC),
                    uiConfig =
                        QSTileUIConfig.Resource(
                            iconRes = com.android.internal.R.drawable.ic_zen_priority_modes,
                            labelRes = R.string.quick_settings_modes_label,
                        ),
                    instanceId = uiEventLogger.getNewInstanceId(),
                    category = TileCategory.CONNECTIVITY,
                )
            } else {
                QSTileConfig(
                    tileSpec = TileSpec.create(DND_TILE_SPEC),
                    uiConfig =
                        QSTileUIConfig.Resource(
                            iconRes = R.drawable.qs_dnd_icon_off,
                            labelRes = R.string.quick_settings_dnd_label,
                        ),
                    instanceId = uiEventLogger.getNewInstanceId(),
                    category = TileCategory.CONNECTIVITY,
                )
            }

        /** Inject ModesTile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(DND_TILE_SPEC)
        fun provideModesTileViewModel(
            factory: QSTileViewModelFactory.Static<ModesTileModel>,
            mapper: ModesTileMapper,
            stateInteractor: ModesTileDataInteractor,
            userActionInteractor: ModesTileUserActionInteractor,
        ): QSTileViewModel =
            if (ModesUi.isEnabled && Flags.qsNewTilesFuture())
                factory.create(
                    TileSpec.create(DND_TILE_SPEC),
                    userActionInteractor,
                    stateInteractor,
                    mapper,
                )
            else StubQSTileViewModel
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

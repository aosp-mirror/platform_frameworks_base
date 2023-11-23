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

package com.android.systemui.statusbar.connectivity

import android.os.UserManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags.SIGNAL_CALLBACK_DEPRECATION
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.AirplaneModeTile
import com.android.systemui.qs.tiles.BluetoothTile
import com.android.systemui.qs.tiles.CastTile
import com.android.systemui.qs.tiles.DataSaverTile
import com.android.systemui.qs.tiles.HotspotTile
import com.android.systemui.qs.tiles.InternetTile
import com.android.systemui.qs.tiles.InternetTileNewImpl
import com.android.systemui.qs.tiles.NfcTile
import com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelFactory
import com.android.systemui.qs.tiles.impl.airplane.domain.AirplaneModeMapper
import com.android.systemui.qs.tiles.impl.airplane.domain.interactor.AirplaneModeTileDataInteractor
import com.android.systemui.qs.tiles.impl.airplane.domain.interactor.AirplaneModeTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.airplane.domain.model.AirplaneModeTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTilePolicy
import com.android.systemui.qs.tiles.viewmodel.QSTileUIConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface ConnectivityModule {

    /** Inject BluetoothTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(BluetoothTile.TILE_SPEC)
    fun bindBluetoothTile(bluetoothTile: BluetoothTile): QSTileImpl<*>

    /** Inject CastTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(CastTile.TILE_SPEC)
    fun bindCastTile(castTile: CastTile): QSTileImpl<*>

    /** Inject HotspotTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(HotspotTile.TILE_SPEC)
    fun bindHotspotTile(hotspotTile: HotspotTile): QSTileImpl<*>

    /** Inject AirplaneModeTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(AirplaneModeTile.TILE_SPEC)
    fun bindAirplaneModeTile(airplaneModeTile: AirplaneModeTile): QSTileImpl<*>

    /** Inject DataSaverTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(DataSaverTile.TILE_SPEC)
    fun bindDataSaverTile(dataSaverTile: DataSaverTile): QSTileImpl<*>

    /** Inject NfcTile into tileMap in QSModule */
    @Binds @IntoMap @StringKey(NfcTile.TILE_SPEC) fun bindNfcTile(nfcTile: NfcTile): QSTileImpl<*>

    companion object {

        const val AIRPLANE_MODE_TILE_SPEC = "airplane"

        /** Inject InternetTile or InternetTileNewImpl into tileMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(InternetTile.TILE_SPEC)
        fun bindInternetTile(
            internetTile: InternetTile,
            newInternetTile: InternetTileNewImpl,
            featureFlags: FeatureFlags,
        ): QSTileImpl<*> =
            if (featureFlags.isEnabled(SIGNAL_CALLBACK_DEPRECATION)) {
                newInternetTile
            } else {
                internetTile
            }

        @Provides
        @IntoMap
        @StringKey(AIRPLANE_MODE_TILE_SPEC)
        fun provideAirplaneModeTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(AIRPLANE_MODE_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.qs_airplane_icon_off,
                        labelRes = R.string.airplane_mode,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                policy = QSTilePolicy.Restricted(UserManager.DISALLOW_AIRPLANE_MODE),
            )

        /** Inject AirplaneModeTile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(AIRPLANE_MODE_TILE_SPEC)
        fun provideAirplaneModeTileViewModel(
            factory: QSTileViewModelFactory.Static<AirplaneModeTileModel>,
            mapper: AirplaneModeMapper,
            stateInteractor: AirplaneModeTileDataInteractor,
            userActionInteractor: AirplaneModeTileUserActionInteractor
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(AIRPLANE_MODE_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )
    }
}

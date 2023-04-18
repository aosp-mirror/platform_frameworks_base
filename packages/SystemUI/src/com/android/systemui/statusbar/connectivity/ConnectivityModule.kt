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

package com.android.systemui.statusbar.connectivity

import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.AirplaneModeTile
import com.android.systemui.qs.tiles.BluetoothTile
import com.android.systemui.qs.tiles.CastTile
import com.android.systemui.qs.tiles.DataSaverTile
import com.android.systemui.qs.tiles.HotspotTile
import com.android.systemui.qs.tiles.InternetTile
import com.android.systemui.qs.tiles.NfcTile
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface ConnectivityModule {

    /** Inject InternetTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(InternetTile.TILE_SPEC)
    fun bindInternetTile(internetTile: InternetTile): QSTileImpl<*>

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
}

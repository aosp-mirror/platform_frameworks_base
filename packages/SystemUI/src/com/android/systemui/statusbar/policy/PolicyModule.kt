/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.AlarmTile
import com.android.systemui.qs.tiles.CameraToggleTile
import com.android.systemui.qs.tiles.DndTile
import com.android.systemui.qs.tiles.FlashlightTile
import com.android.systemui.qs.tiles.LocationTile
import com.android.systemui.qs.tiles.MicrophoneToggleTile
import com.android.systemui.qs.tiles.UiModeNightTile
import com.android.systemui.qs.tiles.WorkModeTile
import dagger.Binds
import dagger.Module
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

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

package com.android.systemui.bluetooth.qsdialog

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger

/** UI Events for the bluetooth tile dialog. */
enum class BluetoothTileDialogUiEvent(val metricId: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "The bluetooth tile dialog is shown") BLUETOOTH_TILE_DIALOG_SHOWN(1493),
    @UiEvent(doc = "The master toggle is clicked") BLUETOOTH_TOGGLE_CLICKED(1494),
    @UiEvent(doc = "Pair new device is clicked") PAIR_NEW_DEVICE_CLICKED(1495),
    @UiEvent(doc = "See all is clicked") SEE_ALL_CLICKED(1496),
    @UiEvent(doc = "Gear icon clicked") DEVICE_GEAR_CLICKED(1497),
    @UiEvent(doc = "Device clicked") DEVICE_CLICKED(1498),
    @UiEvent(doc = "Connected device clicked to active") CONNECTED_DEVICE_SET_ACTIVE(1499),
    @UiEvent(doc = "Saved clicked to connect") SAVED_DEVICE_CONNECT(1500),
    @UiEvent(doc = "Active device clicked to disconnect") ACTIVE_DEVICE_DISCONNECT(1507),
    @UiEvent(doc = "Audio sharing device clicked, do nothing") AUDIO_SHARING_DEVICE_CLICKED(1699),
    @UiEvent(doc = "Available audio sharing device clicked, do nothing")
    AVAILABLE_AUDIO_SHARING_DEVICE_CLICKED(1880),
    @UiEvent(doc = "Connected other device clicked to disconnect")
    CONNECTED_OTHER_DEVICE_DISCONNECT(1508),
    @UiEvent(doc = "The auto on toggle is clicked") BLUETOOTH_AUTO_ON_TOGGLE_CLICKED(1617),
    @UiEvent(doc = "The audio sharing button is clicked")
    BLUETOOTH_AUDIO_SHARING_BUTTON_CLICKED(1700),
    @UiEvent(doc = "Currently broadcasting and a LE audio supported device is clicked")
    LAUNCH_SETTINGS_IN_SHARING_LE_DEVICE_CLICKED(1717),
    @UiEvent(doc = "Currently broadcasting and a non-LE audio supported device is clicked")
    LAUNCH_SETTINGS_IN_SHARING_NON_LE_DEVICE_CLICKED(1718),
    @UiEvent(
        doc = "Not broadcasting, having one connected, another saved LE audio device is clicked"
    )
    LAUNCH_SETTINGS_NOT_SHARING_SAVED_LE_DEVICE_CLICKED(1719),
    @Deprecated(
        "Use case no longer needed",
        ReplaceWith("LAUNCH_SETTINGS_NOT_SHARING_ACTIVE_LE_DEVICE_CLICKED")
    )
    @UiEvent(doc = "Not broadcasting, one of the two connected LE audio devices is clicked")
    LAUNCH_SETTINGS_NOT_SHARING_CONNECTED_LE_DEVICE_CLICKED(1720),
    @UiEvent(doc = "Not broadcasting, having two connected, the active LE audio devices is clicked")
    LAUNCH_SETTINGS_NOT_SHARING_ACTIVE_LE_DEVICE_CLICKED(1881);

    override fun getId() = metricId
}

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

package com.android.systemui.bluetooth.qsdialog

import android.content.Intent
import android.os.Bundle
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface DeviceItemActionInteractor {
    suspend fun onClick(deviceItem: DeviceItem, dialog: SystemUIDialog?) {}

    suspend fun onActionIconClick(deviceItem: DeviceItem, onIntent: (Intent) -> Unit)
}

@SysUISingleton
class DeviceItemActionInteractorImpl
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val uiEventLogger: UiEventLogger,
) : DeviceItemActionInteractor {

    override suspend fun onClick(deviceItem: DeviceItem, dialog: SystemUIDialog?) {
        withContext(backgroundDispatcher) {
            deviceItem.cachedBluetoothDevice.apply {
                when (deviceItem.type) {
                    DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE -> {
                        disconnect()
                        uiEventLogger.log(BluetoothTileDialogUiEvent.ACTIVE_DEVICE_DISCONNECT)
                    }
                    DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE -> {
                        setActive()
                        uiEventLogger.log(BluetoothTileDialogUiEvent.CONNECTED_DEVICE_SET_ACTIVE)
                    }
                    DeviceItemType.CONNECTED_BLUETOOTH_DEVICE -> {
                        disconnect()
                        uiEventLogger.log(
                            BluetoothTileDialogUiEvent.CONNECTED_OTHER_DEVICE_DISCONNECT
                        )
                    }
                    DeviceItemType.SAVED_BLUETOOTH_DEVICE -> {
                        connect()
                        uiEventLogger.log(BluetoothTileDialogUiEvent.SAVED_DEVICE_CONNECT)
                    }
                    DeviceItemType.AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE,
                    DeviceItemType.AVAILABLE_AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE -> {
                        // Do nothing. Should already be handled in
                        // AudioSharingDeviceItemActionInteractor.
                    }
                }
            }
        }
    }

    override suspend fun onActionIconClick(deviceItem: DeviceItem, onIntent: (Intent) -> Unit) {
        withContext(backgroundDispatcher) {
            deviceItem.cachedBluetoothDevice.apply {
                when (deviceItem.type) {
                    DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE,
                    DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE,
                    DeviceItemType.CONNECTED_BLUETOOTH_DEVICE,
                    DeviceItemType.SAVED_BLUETOOTH_DEVICE -> {
                        uiEventLogger.log(BluetoothTileDialogUiEvent.DEVICE_GEAR_CLICKED)
                        val intent =
                            Intent(ACTION_BLUETOOTH_DEVICE_DETAILS).apply {
                                putExtra(
                                    EXTRA_SHOW_FRAGMENT_ARGUMENTS,
                                    Bundle().apply {
                                        putString(
                                            "device_address",
                                            deviceItem.cachedBluetoothDevice.address,
                                        )
                                    },
                                )
                            }
                        onIntent(intent)
                    }
                    DeviceItemType.AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE,
                    DeviceItemType.AVAILABLE_AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE -> {
                        throw IllegalArgumentException("Invalid device type: ${deviceItem.type}")
                        // Throw exception. Should already be handled in
                        // AudioSharingDeviceItemActionInteractor.
                    }
                }
            }
        }
    }

    private companion object {
        const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
        const val ACTION_BLUETOOTH_DEVICE_DETAILS =
            "com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS"
    }
}

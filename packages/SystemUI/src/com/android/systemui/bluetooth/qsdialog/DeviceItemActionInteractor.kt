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

import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Defines interface for click handling of a DeviceItem. */
interface DeviceItemActionInteractor {
    suspend fun onClick(deviceItem: DeviceItem, dialog: SystemUIDialog)
}

@SysUISingleton
open class DeviceItemActionInteractorImpl
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val logger: BluetoothTileDialogLogger,
    private val uiEventLogger: UiEventLogger,
) : DeviceItemActionInteractor {

    override suspend fun onClick(deviceItem: DeviceItem, dialog: SystemUIDialog) {
        withContext(backgroundDispatcher) {
            logger.logDeviceClick(deviceItem.cachedBluetoothDevice.address, deviceItem.type)

            deviceItem.cachedBluetoothDevice.apply {
                when (deviceItem.type) {
                    DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE -> {
                        disconnect()
                        uiEventLogger.log(BluetoothTileDialogUiEvent.ACTIVE_DEVICE_DISCONNECT)
                    }
                    DeviceItemType.AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE -> {
                        uiEventLogger.log(BluetoothTileDialogUiEvent.AUDIO_SHARING_DEVICE_CLICKED)
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
                }
            }
        }
    }
}

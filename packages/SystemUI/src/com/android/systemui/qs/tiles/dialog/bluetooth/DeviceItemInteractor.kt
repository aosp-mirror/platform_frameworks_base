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

package com.android.systemui.qs.tiles.dialog.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioManager
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.bluetooth.BluetoothCallback
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Holds business logic for the Bluetooth Dialog after clicking on the Bluetooth QS tile. */
@SysUISingleton
internal class DeviceItemInteractor
@Inject
constructor(
    private val bluetoothTileDialogRepository: BluetoothTileDialogRepository,
    private val audioManager: AudioManager,
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter(),
    private val localBluetoothManager: LocalBluetoothManager?,
    private val systemClock: SystemClock,
    private val uiEventLogger: UiEventLogger,
    private val logger: BluetoothTileDialogLogger,
    @Application private val coroutineScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {

    private val mutableDeviceItemUpdate: MutableSharedFlow<List<DeviceItem>> =
        MutableSharedFlow(extraBufferCapacity = 1)
    internal val deviceItemUpdate
        get() = mutableDeviceItemUpdate.asSharedFlow()

    internal val deviceItemUpdateRequest: SharedFlow<Unit> =
        conflatedCallbackFlow {
                val listener =
                    object : BluetoothCallback {
                        override fun onActiveDeviceChanged(
                            activeDevice: CachedBluetoothDevice?,
                            bluetoothProfile: Int
                        ) {
                            super.onActiveDeviceChanged(activeDevice, bluetoothProfile)
                            logger.logActiveDeviceChanged(activeDevice?.address, bluetoothProfile)
                            trySendWithFailureLogging(Unit, TAG, "onActiveDeviceChanged")
                        }

                        override fun onProfileConnectionStateChanged(
                            cachedDevice: CachedBluetoothDevice,
                            state: Int,
                            bluetoothProfile: Int
                        ) {
                            super.onProfileConnectionStateChanged(
                                cachedDevice,
                                state,
                                bluetoothProfile
                            )
                            logger.logProfileConnectionStateChanged(
                                cachedDevice.address,
                                state.toString(),
                                bluetoothProfile
                            )
                            trySendWithFailureLogging(Unit, TAG, "onProfileConnectionStateChanged")
                        }

                        override fun onAclConnectionStateChanged(
                            cachedDevice: CachedBluetoothDevice,
                            state: Int
                        ) {
                            super.onAclConnectionStateChanged(cachedDevice, state)
                            // Listen only when a device is disconnecting
                            if (state == 0) {
                                trySendWithFailureLogging(Unit, TAG, "onAclConnectionStateChanged")
                            }
                        }
                    }
                localBluetoothManager?.eventManager?.registerCallback(listener)
                awaitClose { localBluetoothManager?.eventManager?.unregisterCallback(listener) }
            }
            .shareIn(coroutineScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0))

    private var deviceItemFactoryList: List<DeviceItemFactory> =
        listOf(
            ActiveMediaDeviceItemFactory(),
            AvailableMediaDeviceItemFactory(),
            ConnectedDeviceItemFactory(),
            SavedDeviceItemFactory()
        )

    private var displayPriority: List<DeviceItemType> =
        listOf(
            DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE,
            DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE,
            DeviceItemType.CONNECTED_BLUETOOTH_DEVICE,
            DeviceItemType.SAVED_BLUETOOTH_DEVICE,
        )

    internal suspend fun updateDeviceItems(context: Context, trigger: DeviceFetchTrigger) {
        withContext(backgroundDispatcher) {
            val start = systemClock.elapsedRealtime()
            val deviceItems =
                bluetoothTileDialogRepository.cachedDevices
                    .mapNotNull { cachedDevice ->
                        deviceItemFactoryList
                            .firstOrNull { it.isFilterMatched(cachedDevice, audioManager) }
                            ?.create(context, cachedDevice)
                    }
                    .sort(displayPriority, bluetoothAdapter?.mostRecentlyConnectedDevices)

            // Only emit when the job is not cancelled
            if (isActive) {
                mutableDeviceItemUpdate.tryEmit(deviceItems)
                logger.logDeviceFetch(
                    JobStatus.FINISHED,
                    trigger,
                    systemClock.elapsedRealtime() - start
                )
            } else {
                logger.logDeviceFetch(
                    JobStatus.CANCELLED,
                    trigger,
                    systemClock.elapsedRealtime() - start
                )
            }
        }
    }

    private fun List<DeviceItem>.sort(
        displayPriority: List<DeviceItemType>,
        mostRecentlyConnectedDevices: List<BluetoothDevice>?
    ): List<DeviceItem> {
        return this.sortedWith(
            compareBy<DeviceItem> { displayPriority.indexOf(it.type) }
                .thenBy {
                    mostRecentlyConnectedDevices?.indexOf(it.cachedBluetoothDevice.device) ?: 0
                }
        )
    }

    internal suspend fun updateDeviceItemOnClick(deviceItem: DeviceItem) {
        withContext(backgroundDispatcher) {
            logger.logDeviceClick(deviceItem.cachedBluetoothDevice.address, deviceItem.type)

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
                }
            }
        }
    }

    internal fun setDeviceItemFactoryListForTesting(list: List<DeviceItemFactory>) {
        deviceItemFactoryList = list
    }

    internal fun setDisplayPriorityForTesting(list: List<DeviceItemType>) {
        displayPriority = list
    }

    companion object {
        private const val TAG = "DeviceItemInteractor"
    }
}

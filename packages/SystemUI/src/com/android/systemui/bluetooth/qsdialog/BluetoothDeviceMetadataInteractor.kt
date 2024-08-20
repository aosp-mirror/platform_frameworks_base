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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class BluetoothDeviceMetadataInteractor
@Inject
constructor(
    deviceItemInteractor: DeviceItemInteractor,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val logger: BluetoothTileDialogLogger,
    @Background private val executor: Executor,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    private fun metadataUpdateForDevice(bluetoothDevice: BluetoothDevice): Flow<Unit> =
        conflatedCallbackFlow {
            val metadataChangedListener =
                BluetoothAdapter.OnMetadataChangedListener { device, key, value ->
                    when (key) {
                        BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY,
                        BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY,
                        BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY,
                        BluetoothDevice.METADATA_MAIN_BATTERY -> {
                            trySendWithFailureLogging(Unit, TAG, "onMetadataChanged")
                            logger.logBatteryChanged(device.address, key, value)
                        }
                    }
                }
            bluetoothAdapter?.addOnMetadataChangedListener(
                bluetoothDevice,
                executor,
                metadataChangedListener
            )
            awaitClose {
                bluetoothAdapter?.removeOnMetadataChangedListener(
                    bluetoothDevice,
                    metadataChangedListener
                )
            }
        }

    val metadataUpdate: Flow<Unit> =
        deviceItemInteractor.deviceItemUpdate
            .distinctUntilChangedBy { it.bluetoothDevices }
            .flatMapLatest { items ->
                items.bluetoothDevices.map { device -> metadataUpdateForDevice(device) }.merge()
            }
            .flowOn(backgroundDispatcher)

    private companion object {
        private const val TAG = "BluetoothDeviceMetadataInteractor"
        private val List<DeviceItem>.bluetoothDevices: Set<BluetoothDevice>
            get() =
                flatMapTo(mutableSetOf()) { item ->
                    listOf(item.cachedBluetoothDevice.device) +
                        item.cachedBluetoothDevice.memberDevice.map { it.device }
                }
    }
}

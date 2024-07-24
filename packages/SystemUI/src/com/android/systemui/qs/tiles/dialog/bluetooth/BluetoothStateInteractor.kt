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
import android.bluetooth.BluetoothAdapter.STATE_OFF
import android.bluetooth.BluetoothAdapter.STATE_ON
import com.android.settingslib.bluetooth.BluetoothCallback
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Holds business logic for the Bluetooth Dialog's bluetooth and device connection state */
@SysUISingleton
internal class BluetoothStateInteractor
@Inject
constructor(
    private val localBluetoothManager: LocalBluetoothManager?,
    private val logger: BluetoothTileDialogLogger,
    @Application private val coroutineScope: CoroutineScope,
) {

    internal val bluetoothStateUpdate: StateFlow<Boolean?> =
        conflatedCallbackFlow {
                val listener =
                    object : BluetoothCallback {
                        override fun onBluetoothStateChanged(bluetoothState: Int) {
                            if (bluetoothState == STATE_ON || bluetoothState == STATE_OFF) {
                                super.onBluetoothStateChanged(bluetoothState)
                                logger.logBluetoothState(
                                    BluetoothStateStage.BLUETOOTH_STATE_CHANGE_RECEIVED,
                                    BluetoothAdapter.nameForState(bluetoothState)
                                )
                                trySendWithFailureLogging(
                                    bluetoothState == STATE_ON,
                                    TAG,
                                    "onBluetoothStateChanged"
                                )
                            }
                        }
                    }
                localBluetoothManager?.eventManager?.registerCallback(listener)
                awaitClose { localBluetoothManager?.eventManager?.unregisterCallback(listener) }
            }
            .stateIn(
                coroutineScope,
                SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
                initialValue = null
            )

    internal var isBluetoothEnabled: Boolean
        get() = localBluetoothManager?.bluetoothAdapter?.isEnabled == true
        set(value) {
            if (isBluetoothEnabled != value) {
                localBluetoothManager?.bluetoothAdapter?.apply {
                    if (value) enable() else disable()
                    logger.logBluetoothState(
                        BluetoothStateStage.BLUETOOTH_STATE_VALUE_SET,
                        value.toString()
                    )
                }
            }
        }

    companion object {
        private const val TAG = "BtStateInteractor"
    }
}

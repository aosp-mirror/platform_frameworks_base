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
import android.util.Log
import com.android.settingslib.bluetooth.BluetoothCallback
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Repository class responsible for managing the Bluetooth Auto-On feature settings for the current
 * user.
 */
@SysUISingleton
class BluetoothAutoOnRepository
@Inject
constructor(
    localBluetoothManager: LocalBluetoothManager?,
    private val bluetoothAdapter: BluetoothAdapter?,
    @Application private val coroutineScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {

    // Flow representing the auto on state for the current user
    internal val isAutoOn: Flow<Boolean> =
        localBluetoothManager?.eventManager?.let { eventManager ->
            conflatedCallbackFlow {
                    val listener =
                        object : BluetoothCallback {
                            override fun onAutoOnStateChanged(autoOnState: Int) {
                                super.onAutoOnStateChanged(autoOnState)
                                if (
                                    autoOnState == BluetoothAdapter.AUTO_ON_STATE_ENABLED ||
                                        autoOnState == BluetoothAdapter.AUTO_ON_STATE_DISABLED
                                ) {
                                    trySendWithFailureLogging(
                                        autoOnState == BluetoothAdapter.AUTO_ON_STATE_ENABLED,
                                        TAG,
                                        "onAutoOnStateChanged"
                                    )
                                }
                            }
                        }
                    eventManager.registerCallback(listener)
                    awaitClose { eventManager.unregisterCallback(listener) }
                }
                .onStart { emit(isAutoOnEnabled()) }
                .flowOn(backgroundDispatcher)
                .stateIn(
                    coroutineScope,
                    SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
                    initialValue = false
                )
        }
            ?: flowOf(false)

    /**
     * Checks if the auto on feature is supported for the current user.
     *
     * @throws Exception if an error occurs while checking auto-on support.
     */
    suspend fun isAutoOnSupported(): Boolean =
        withContext(backgroundDispatcher) {
            try {
                bluetoothAdapter?.isAutoOnSupported ?: false
            } catch (e: Exception) {
                // Server could throw TimeoutException, InterruptedException or ExecutionException
                Log.e(TAG, "Error calling isAutoOnSupported", e)
                false
            } catch (e: NoSuchMethodError) {
                // TODO(b/346716614): Remove this when the flag is cleaned up.
                Log.e(TAG, "Non-existed api isAutoOnSupported", e)
                false
            }
        }

    /** Sets the Bluetooth Auto-On for the current user. */
    suspend fun setAutoOn(value: Boolean) {
        withContext(backgroundDispatcher) {
            try {
                bluetoothAdapter?.setAutoOnEnabled(value)
            } catch (e: Exception) {
                // Server could throw IllegalStateException, TimeoutException, InterruptedException
                // or ExecutionException
                Log.e(TAG, "Error calling setAutoOnEnabled", e)
            } catch (e: NoSuchMethodError) {
                // TODO(b/346716614): Remove this when the flag is cleaned up.
                Log.e(TAG, "Non-existed api setAutoOn", e)
            }
        }
    }

    private suspend fun isAutoOnEnabled() =
        withContext(backgroundDispatcher) {
            try {
                bluetoothAdapter?.isAutoOnEnabled ?: false
            } catch (e: Exception) {
                // Server could throw IllegalStateException, TimeoutException, InterruptedException
                // or ExecutionException
                Log.e(TAG, "Error calling isAutoOnEnabled", e)
                false
            } catch (e: NoSuchMethodError) {
                // TODO(b/346716614): Remove this when the flag is cleaned up.
                Log.e(TAG, "Non-existed api isAutoOnEnabled", e)
                false
            }
        }

    private companion object {
        const val TAG = "BluetoothAutoOnRepository"
    }
}

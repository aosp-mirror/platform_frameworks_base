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

import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Holds business logic for the audio sharing state. */
interface AudioSharingInteractor {
    suspend fun isAvailableAudioSharingMediaBluetoothDevice(
        cachedBluetoothDevice: CachedBluetoothDevice
    ): Boolean
}

@SysUISingleton
class AudioSharingInteractorImpl
@Inject
constructor(
    private val localBluetoothManager: LocalBluetoothManager?,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : AudioSharingInteractor {

    override suspend fun isAvailableAudioSharingMediaBluetoothDevice(
        cachedBluetoothDevice: CachedBluetoothDevice
    ): Boolean {
        return withContext(backgroundDispatcher) {
            BluetoothUtils.isAvailableAudioSharingMediaBluetoothDevice(
                cachedBluetoothDevice,
                localBluetoothManager
            )
        }
    }
}

@SysUISingleton
class AudioSharingInteractorEmptyImpl @Inject constructor() : AudioSharingInteractor {
    override suspend fun isAvailableAudioSharingMediaBluetoothDevice(
        cachedBluetoothDevice: CachedBluetoothDevice
    ) = false
}

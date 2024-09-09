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

import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface AudioSharingRepository {

    suspend fun setActive(cachedBluetoothDevice: CachedBluetoothDevice)

    suspend fun startAudioSharing()
}

@SysUISingleton
class AudioSharingRepositoryImpl(
    private val localBluetoothManager: LocalBluetoothManager,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : AudioSharingRepository {

    private val leAudioBroadcastProfile: LocalBluetoothLeBroadcast?
        get() = localBluetoothManager.profileManager?.leAudioBroadcastProfile

    override suspend fun setActive(cachedBluetoothDevice: CachedBluetoothDevice) {
        withContext(backgroundDispatcher) { cachedBluetoothDevice.setActive() }
    }

    override suspend fun startAudioSharing() {
        withContext(backgroundDispatcher) { leAudioBroadcastProfile?.startPrivateBroadcast() }
    }
}

@SysUISingleton
class AudioSharingRepositoryEmptyImpl : AudioSharingRepository {

    override suspend fun setActive(cachedBluetoothDevice: CachedBluetoothDevice) {}

    override suspend fun startAudioSharing() {}
}

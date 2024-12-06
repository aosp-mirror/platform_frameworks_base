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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeAudioSharingRepository : AudioSharingRepository {
    private var mutableAvailable: Boolean = false

    private val mutableInAudioSharing: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val mutableAudioSourceStateUpdate = MutableSharedFlow<Unit>()

    var sourceAdded: Boolean = false
        private set

    private var profile: LocalBluetoothLeBroadcast? = null

    override val leAudioBroadcastProfile: LocalBluetoothLeBroadcast?
        get() = profile

    override val audioSourceStateUpdate: Flow<Unit> = mutableAudioSourceStateUpdate

    override val inAudioSharing: StateFlow<Boolean> = mutableInAudioSharing

    override suspend fun audioSharingAvailable(): Boolean = mutableAvailable

    override suspend fun addSource() {
        sourceAdded = true
    }

    override suspend fun setActive(cachedBluetoothDevice: CachedBluetoothDevice) {}

    override suspend fun startAudioSharing() {}

    fun setAudioSharingAvailable(available: Boolean) {
        mutableAvailable = available
    }

    fun setInAudioSharing(state: Boolean) {
        mutableInAudioSharing.value = state
    }

    fun setLeAudioBroadcastProfile(leAudioBroadcastProfile: LocalBluetoothLeBroadcast?) {
        profile = leAudioBroadcastProfile
    }

    fun emitAudioSourceStateUpdate() {
        mutableAudioSourceStateUpdate.tryEmit(Unit)
    }
}

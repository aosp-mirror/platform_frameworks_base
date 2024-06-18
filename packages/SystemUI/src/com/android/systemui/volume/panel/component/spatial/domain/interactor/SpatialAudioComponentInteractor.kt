/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.systemui.volume.panel.component.spatial.domain.interactor

import android.media.AudioDeviceAttributes
import android.media.AudioDeviceInfo
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.media.BluetoothMediaDevice
import com.android.settingslib.media.domain.interactor.SpatializerInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputInteractor
import com.android.systemui.volume.panel.component.spatial.domain.model.SpatialAudioAvailabilityModel
import com.android.systemui.volume.panel.component.spatial.domain.model.SpatialAudioEnabledModel
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Provides an ability to access and update spatial audio and head tracking state.
 *
 * Head tracking is a sub-feature of spatial audio. This means that it requires spatial audio to be
 * available for it to be available. And spatial audio to be enabled for it to be enabled.
 */
@VolumePanelScope
class SpatialAudioComponentInteractor
@Inject
constructor(
    mediaOutputInteractor: MediaOutputInteractor,
    private val spatializerInteractor: SpatializerInteractor,
    @VolumePanelScope private val coroutineScope: CoroutineScope,
) {

    private val changes = MutableSharedFlow<Unit>()
    private val currentAudioDeviceAttributes: StateFlow<AudioDeviceAttributes?> =
        mediaOutputInteractor.currentConnectedDevice
            .map { mediaDevice ->
                mediaDevice ?: return@map null
                val btDevice: CachedBluetoothDevice =
                    (mediaDevice as? BluetoothMediaDevice)?.cachedDevice ?: return@map null
                btDevice.getAudioDeviceAttributes()
            }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), null)

    /**
     * Returns spatial audio availability model. It can be:
     * - unavailable
     * - only spatial audio is available
     * - spatial audio and head tracking are available
     */
    val isAvailable: StateFlow<SpatialAudioAvailabilityModel> =
        combine(
                currentAudioDeviceAttributes,
                changes.onStart { emit(Unit) },
                spatializerInteractor.isHeadTrackingAvailable,
            ) { attributes, _, isHeadTrackingAvailable ->
                attributes ?: return@combine SpatialAudioAvailabilityModel.Unavailable
                if (isHeadTrackingAvailable) {
                    return@combine SpatialAudioAvailabilityModel.HeadTracking
                }
                if (spatializerInteractor.isSpatialAudioAvailable(attributes)) {
                    return@combine SpatialAudioAvailabilityModel.SpatialAudio
                }
                SpatialAudioAvailabilityModel.Unavailable
            }
            .stateIn(
                coroutineScope,
                SharingStarted.Eagerly,
                SpatialAudioAvailabilityModel.Unavailable,
            )

    /**
     * Returns spatial audio enabled/disabled model. It can be
     * - disabled
     * - only spatial audio is enabled
     * - spatial audio and head tracking are enabled
     */
    val isEnabled: StateFlow<SpatialAudioEnabledModel> =
        combine(
                changes.onStart { emit(Unit) },
                currentAudioDeviceAttributes,
                isAvailable,
            ) { _, attributes, isAvailable ->
                if (isAvailable is SpatialAudioAvailabilityModel.Unavailable) {
                    return@combine SpatialAudioEnabledModel.Disabled
                }
                attributes ?: return@combine SpatialAudioEnabledModel.Disabled
                if (spatializerInteractor.isHeadTrackingEnabled(attributes)) {
                    return@combine SpatialAudioEnabledModel.HeadTrackingEnabled
                }
                if (spatializerInteractor.isSpatialAudioEnabled(attributes)) {
                    return@combine SpatialAudioEnabledModel.SpatialAudioEnabled
                }
                SpatialAudioEnabledModel.Disabled
            }
            .stateIn(
                coroutineScope,
                SharingStarted.Eagerly,
                SpatialAudioEnabledModel.Disabled,
            )

    /**
     * Sets current [isEnabled] to a specific [SpatialAudioEnabledModel]. It
     * - disables both spatial audio and head tracking
     * - enables only spatial audio
     * - enables both spatial audio and head tracking
     */
    suspend fun setEnabled(model: SpatialAudioEnabledModel) {
        val attributes = currentAudioDeviceAttributes.value ?: return
        spatializerInteractor.setSpatialAudioEnabled(
            attributes,
            model is SpatialAudioEnabledModel.SpatialAudioEnabled,
        )
        spatializerInteractor.setHeadTrackingEnabled(
            attributes,
            model is SpatialAudioEnabledModel.HeadTrackingEnabled,
        )
        changes.emit(Unit)
    }

    private suspend fun CachedBluetoothDevice.getAudioDeviceAttributes(): AudioDeviceAttributes? {
        return listOf(
                AudioDeviceAttributes(
                    AudioDeviceAttributes.ROLE_OUTPUT,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    address
                ),
                AudioDeviceAttributes(
                    AudioDeviceAttributes.ROLE_OUTPUT,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    address
                ),
                AudioDeviceAttributes(
                    AudioDeviceAttributes.ROLE_OUTPUT,
                    AudioDeviceInfo.TYPE_BLE_BROADCAST,
                    address
                ),
                AudioDeviceAttributes(
                    AudioDeviceAttributes.ROLE_OUTPUT,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    address
                ),
                AudioDeviceAttributes(
                    AudioDeviceAttributes.ROLE_OUTPUT,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                    address
                )
            )
            .firstOrNull { spatializerInteractor.isSpatialAudioAvailable(it) }
    }
}

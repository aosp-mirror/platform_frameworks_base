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

import android.bluetooth.BluetoothProfile
import android.media.AudioDeviceAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothProfile
import com.android.settingslib.flags.Flags
import com.android.settingslib.media.domain.interactor.SpatializerInteractor
import com.android.settingslib.volume.data.repository.AudioRepository
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.volume.domain.interactor.AudioOutputInteractor
import com.android.systemui.volume.domain.model.AudioOutputDevice
import com.android.systemui.volume.panel.component.spatial.domain.model.SpatialAudioAvailabilityModel
import com.android.systemui.volume.panel.component.spatial.domain.model.SpatialAudioEnabledModel
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

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
    audioOutputInteractor: AudioOutputInteractor,
    private val spatializerInteractor: SpatializerInteractor,
    private val audioRepository: AudioRepository,
    @Background private val backgroundCoroutineContext: CoroutineContext,
    @VolumePanelScope private val coroutineScope: CoroutineScope,
) {

    private val changes = MutableSharedFlow<Unit>()
    private val currentAudioDeviceAttributes: StateFlow<AudioDeviceAttributes?> =
        audioOutputInteractor.currentAudioDevice
            .map { audioDevice ->
                if (audioDevice is AudioOutputDevice.Unknown) {
                    builtinSpeaker
                } else {
                    audioDevice.getAudioDeviceAttributes()
                }
            }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), builtinSpeaker)

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
            ) { attributes, _,
                ->
                attributes ?: return@combine SpatialAudioAvailabilityModel.Unavailable
                if (spatializerInteractor.isSpatialAudioAvailable(attributes)) {
                    if (spatializerInteractor.isHeadTrackingAvailable(attributes)) {
                        return@combine SpatialAudioAvailabilityModel.HeadTracking
                    }
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
                if (spatializerInteractor.isSpatialAudioEnabled(attributes)) {
                    if (spatializerInteractor.isHeadTrackingEnabled(attributes)) {
                        return@combine SpatialAudioEnabledModel.HeadTrackingEnabled
                    }
                    return@combine SpatialAudioEnabledModel.SpatialAudioEnabled
                }
                SpatialAudioEnabledModel.Disabled
            }
            .stateIn(
                coroutineScope,
                SharingStarted.Eagerly,
                SpatialAudioEnabledModel.Unknown,
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

    private suspend fun AudioOutputDevice.getAudioDeviceAttributes(): AudioDeviceAttributes? {
        return when (this) {
            is AudioOutputDevice.BuiltIn -> builtinSpeaker
            is AudioOutputDevice.Bluetooth -> {
                if (Flags.enableDeterminingSpatialAudioAttributesByProfile()) {
                    getAudioDeviceAttributesByBluetoothProfile(cachedBluetoothDevice)
                } else {
                    listOf(
                            AudioDeviceAttributes(
                                AudioDeviceAttributes.ROLE_OUTPUT,
                                AudioDeviceInfo.TYPE_BLE_HEADSET,
                                cachedBluetoothDevice.address,
                            ),
                            AudioDeviceAttributes(
                                AudioDeviceAttributes.ROLE_OUTPUT,
                                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                                cachedBluetoothDevice.address,
                            ),
                            AudioDeviceAttributes(
                                AudioDeviceAttributes.ROLE_OUTPUT,
                                AudioDeviceInfo.TYPE_BLE_BROADCAST,
                                cachedBluetoothDevice.address,
                            ),
                            AudioDeviceAttributes(
                                AudioDeviceAttributes.ROLE_OUTPUT,
                                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                                cachedBluetoothDevice.address,
                            ),
                            AudioDeviceAttributes(
                                AudioDeviceAttributes.ROLE_OUTPUT,
                                AudioDeviceInfo.TYPE_HEARING_AID,
                                cachedBluetoothDevice.address,
                            )
                        )
                        .firstOrNull { spatializerInteractor.isSpatialAudioAvailable(it) }
                }
            }
            else -> null
        }
    }

    private suspend fun getAudioDeviceAttributesByBluetoothProfile(
        cachedBluetoothDevice: CachedBluetoothDevice
    ): AudioDeviceAttributes? =
        withContext(backgroundCoroutineContext) {
            cachedBluetoothDevice.profiles
                .firstOrNull {
                    it.profileId in audioProfiles && it.isEnabled(cachedBluetoothDevice.device)
                }
                ?.let { profile: LocalBluetoothProfile ->
                    when (profile.profileId) {
                        BluetoothProfile.A2DP -> {
                            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        }
                        BluetoothProfile.LE_AUDIO -> {
                            when (
                                audioRepository.getBluetoothAudioDeviceCategory(
                                    cachedBluetoothDevice.address
                                )
                            ) {
                                AudioManager.AUDIO_DEVICE_CATEGORY_SPEAKER ->
                                    AudioDeviceInfo.TYPE_BLE_SPEAKER
                                else -> AudioDeviceInfo.TYPE_BLE_HEADSET
                            }
                        }
                        BluetoothProfile.HEARING_AID -> {
                            AudioDeviceInfo.TYPE_HEARING_AID
                        }
                        else -> null
                    }
                }
                ?.let {
                    AudioDeviceAttributes(
                        AudioDeviceAttributes.ROLE_OUTPUT,
                        it,
                        cachedBluetoothDevice.address,
                    )
                }
        }

    private companion object {
        val builtinSpeaker =
            AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                ""
            )
        val audioProfiles =
            setOf(BluetoothProfile.A2DP, BluetoothProfile.LE_AUDIO, BluetoothProfile.HEARING_AID)
    }
}

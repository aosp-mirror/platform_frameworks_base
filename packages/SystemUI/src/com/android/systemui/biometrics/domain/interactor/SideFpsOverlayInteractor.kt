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

package com.android.systemui.biometrics.domain.interactor

import android.hardware.biometrics.SensorLocationInternal
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepository
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/** Business logic for SideFps overlay offsets. */
interface SideFpsOverlayInteractor {

    /** The displayId of the current display. */
    val displayId: Flow<String>

    /** Overlay offsets corresponding to given displayId. */
    val overlayOffsets: Flow<SensorLocationInternal>

    /** Called on display changes, used to keep the display state in sync */
    fun onDisplayChanged(displayId: String)
}

@SysUISingleton
class SideFpsOverlayInteractorImpl
@Inject
constructor(fingerprintPropertyRepository: FingerprintPropertyRepository) :
    SideFpsOverlayInteractor {

    private val _displayId: MutableStateFlow<String> = MutableStateFlow("")
    override val displayId: Flow<String> = _displayId.asStateFlow()

    override val overlayOffsets: Flow<SensorLocationInternal> =
        combine(displayId, fingerprintPropertyRepository.sensorLocations) { displayId, offsets ->
            offsets[displayId] ?: SensorLocationInternal.DEFAULT
        }

    override fun onDisplayChanged(displayId: String) {
        _displayId.value = displayId
    }

    companion object {
        private const val TAG = "SideFpsOverlayInteractorImpl"
    }
}

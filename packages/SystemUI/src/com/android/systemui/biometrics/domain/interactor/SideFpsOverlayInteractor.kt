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
import android.util.Log
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepository
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** Business logic for SideFps overlay offsets. */
interface SideFpsOverlayInteractor {

    /** Get the corresponding offsets based on different displayId. */
    fun getOverlayOffsets(displayId: String): SensorLocationInternal
}

@SysUISingleton
class SideFpsOverlayInteractorImpl
@Inject
constructor(private val fingerprintPropertyRepository: FingerprintPropertyRepository) :
    SideFpsOverlayInteractor {

    override fun getOverlayOffsets(displayId: String): SensorLocationInternal {
        val offsets = fingerprintPropertyRepository.sensorLocations.value
        return if (offsets.containsKey(displayId)) {
            offsets[displayId]!!
        } else {
            Log.w(TAG, "No location specified for display: $displayId")
            offsets[""]!!
        }
    }

    companion object {
        private const val TAG = "SideFpsOverlayInteractorImpl"
    }
}

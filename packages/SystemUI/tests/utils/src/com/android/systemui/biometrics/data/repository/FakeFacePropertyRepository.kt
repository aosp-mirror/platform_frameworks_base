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
 *
 */

package com.android.systemui.biometrics.data.repository

import android.graphics.Point
import com.android.systemui.biometrics.shared.model.LockoutMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeFacePropertyRepository : FacePropertyRepository {
    private val faceSensorInfo = MutableStateFlow<FaceSensorInfo?>(null)
    override val sensorInfo: StateFlow<FaceSensorInfo?>
        get() = faceSensorInfo

    private val lockoutModesForUser = mutableMapOf<Int, LockoutMode>()

    private val faceSensorLocation = MutableStateFlow<Point?>(null)
    override val sensorLocation: StateFlow<Point?>
        get() = faceSensorLocation

    private val currentCameraInfo = MutableStateFlow<CameraInfo?>(null)
    override val cameraInfo: StateFlow<CameraInfo?>
        get() = currentCameraInfo

    fun setLockoutMode(userId: Int, mode: LockoutMode) {
        lockoutModesForUser[userId] = mode
    }
    override suspend fun getLockoutMode(userId: Int): LockoutMode {
        return lockoutModesForUser[userId]!!
    }

    fun setSensorInfo(value: FaceSensorInfo?) {
        faceSensorInfo.value = value
    }

    fun setSensorLocation(value: Point?) {
        faceSensorLocation.value = value
    }

    fun setCameraIno(value: CameraInfo?) {
        currentCameraInfo.value = value
    }
}

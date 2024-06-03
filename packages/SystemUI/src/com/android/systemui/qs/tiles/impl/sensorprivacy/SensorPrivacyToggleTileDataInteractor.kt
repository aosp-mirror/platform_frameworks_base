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

package com.android.systemui.qs.tiles.impl.sensorprivacy

import android.hardware.SensorPrivacyManager.Sensors.CAMERA
import android.hardware.SensorPrivacyManager.Sensors.MICROPHONE
import android.hardware.SensorPrivacyManager.Sensors.Sensor
import android.os.UserHandle
import android.provider.DeviceConfig
import android.util.Log
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.sensorprivacy.domain.model.SensorPrivacyToggleTileModel
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/** Observes SensorPrivacyToggle mode state changes providing the [SensorPrivacyToggleTileModel]. */
class SensorPrivacyToggleTileDataInteractor
@AssistedInject
constructor(
    @Background private val bgCoroutineContext: CoroutineContext,
    private val privacyController: IndividualSensorPrivacyController,
    @Assisted @Sensor private val sensorId: Int,
) : QSTileDataInteractor<SensorPrivacyToggleTileModel> {
    @AssistedFactory
    interface Factory {
        fun create(@Sensor id: Int): SensorPrivacyToggleTileDataInteractor
    }

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<SensorPrivacyToggleTileModel> =
        conflatedCallbackFlow {
                val callback =
                    IndividualSensorPrivacyController.Callback { sensor, blocked ->
                        if (sensor == sensorId) trySend(SensorPrivacyToggleTileModel(blocked))
                    }
                privacyController.addCallback(callback) // does not emit an initial state
                awaitClose { privacyController.removeCallback(callback) }
            }
            .onStart {
                emit(SensorPrivacyToggleTileModel(privacyController.isSensorBlocked(sensorId)))
            }
            .distinctUntilChanged()
            .flowOn(bgCoroutineContext)

    override fun availability(user: UserHandle) =
        flow { emit(isAvailable()) }.flowOn(bgCoroutineContext)

    private suspend fun isAvailable(): Boolean {
        return privacyController.supportsSensorToggle(sensorId) && isSensorDeviceConfigSet()
    }

    private suspend fun isSensorDeviceConfigSet(): Boolean =
        withContext(bgCoroutineContext) {
            try {
                val deviceConfigName = getDeviceConfigName(sensorId)
                return@withContext DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_PRIVACY,
                    deviceConfigName,
                    true
                )
            } catch (exception: IllegalArgumentException) {
                Log.w(
                    TAG,
                    "isDeviceConfigSet for sensorId $sensorId: " +
                        "Defaulting to true due to exception. ",
                    exception
                )
                return@withContext true
            }
        }

    private fun getDeviceConfigName(sensorId: Int): String {
        if (sensorId == MICROPHONE) {
            return "mic_toggle_enabled"
        } else if (sensorId == CAMERA) {
            return "camera_toggle_enabled"
        } else {
            throw IllegalArgumentException("getDeviceConfigName: unexpected sensorId: $sensorId")
        }
    }

    private companion object {
        const val TAG = "SensorPrivacyToggleTileException"
    }
}

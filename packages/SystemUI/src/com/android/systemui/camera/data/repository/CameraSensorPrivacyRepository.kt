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

package com.android.systemui.camera.data.repository

import android.hardware.SensorPrivacyManager
import android.hardware.SensorPrivacyManager.Sensors.CAMERA
import android.os.UserHandle
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

interface CameraSensorPrivacyRepository {
    /** Tracks whether camera sensor privacy is enabled. */
    fun isEnabled(userHandle: UserHandle): StateFlow<Boolean>
}

@SysUISingleton
class CameraSensorPrivacyRepositoryImpl
@Inject
constructor(
    @Background private val bgCoroutineContext: CoroutineContext,
    @Application private val scope: CoroutineScope,
    private val privacyManager: SensorPrivacyManager,
) : CameraSensorPrivacyRepository {
    private val userMap = mutableMapOf<Int, StateFlow<Boolean>>()

    /** Whether camera sensor privacy is enabled */
    override fun isEnabled(userHandle: UserHandle): StateFlow<Boolean> =
        userMap.getOrPut(userHandle.identifier) {
            privacyManager
                .isEnabled(userHandle)
                .flowOn(bgCoroutineContext)
                .stateIn(scope, SharingStarted.WhileSubscribed(), false)
        }
}

fun SensorPrivacyManager.isEnabled(userHandle: UserHandle): Flow<Boolean> {
    return conflatedCallbackFlow {
            val privacyCallback =
                SensorPrivacyManager.OnSensorPrivacyChangedListener { sensor, enabled ->
                    if (sensor == CAMERA) {
                        trySend(enabled)
                    }
                }
            addSensorPrivacyListener(CAMERA, userHandle.identifier, privacyCallback)
            awaitClose { removeSensorPrivacyListener(privacyCallback) }
        }
        .onStart { emit(isSensorPrivacyEnabled(SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE, CAMERA)) }
        .distinctUntilChanged()
}

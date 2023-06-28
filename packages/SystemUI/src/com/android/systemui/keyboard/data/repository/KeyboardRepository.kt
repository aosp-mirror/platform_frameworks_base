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

package com.android.systemui.keyboard.data.repository

import android.hardware.input.InputManager
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shared.model.BacklightModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

interface KeyboardRepository {
    val keyboardConnected: Flow<Boolean>
    val backlight: Flow<BacklightModel>
}

@SysUISingleton
class KeyboardRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val inputManager: InputManager,
) : KeyboardRepository {

    private val connectedDeviceIds: Flow<Set<Int>> =
        conflatedCallbackFlow {
                fun send(element: Set<Int>) = trySendWithFailureLogging(element, TAG)

                var connectedKeyboards = inputManager.inputDeviceIds.toSet()
                val listener =
                    object : InputManager.InputDeviceListener {
                        override fun onInputDeviceAdded(deviceId: Int) {
                            connectedKeyboards = connectedKeyboards + deviceId
                            send(connectedKeyboards)
                        }

                        override fun onInputDeviceChanged(deviceId: Int) = Unit

                        override fun onInputDeviceRemoved(deviceId: Int) {
                            connectedKeyboards = connectedKeyboards - deviceId
                            send(connectedKeyboards)
                        }
                    }
                send(connectedKeyboards)
                inputManager.registerInputDeviceListener(listener, /* handler= */ null)
                awaitClose { inputManager.unregisterInputDeviceListener(listener) }
            }
            .shareIn(
                scope = applicationScope,
                started = SharingStarted.Lazily,
                replay = 1,
            )

    override val keyboardConnected: Flow<Boolean> =
        connectedDeviceIds
            .map { it.any { deviceId -> isPhysicalFullKeyboard(deviceId) } }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    override val backlight: Flow<BacklightModel> =
        conflatedCallbackFlow {
            // TODO(b/268645734) register BacklightListener
        }

    private fun isPhysicalFullKeyboard(deviceId: Int): Boolean {
        val device = inputManager.getInputDevice(deviceId)
        return !device.isVirtual && device.isFullKeyboard
    }

    companion object {
        const val TAG = "KeyboardRepositoryImpl"
    }
}

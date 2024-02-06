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

package com.android.systemui.biometrics.data.repository

import android.content.Context
import android.hardware.devicestate.DeviceStateManager
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
import android.os.Handler
import android.util.Size
import android.view.DisplayInfo
import com.android.app.tracing.traceSection
import com.android.internal.util.ArrayUtils
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.toDisplayRotation
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Repository for the current state of the display */
interface DisplayStateRepository {
    /**
     * If true, the direction rotation is applied to get to an application's requested orientation
     * is reversed. Normally, the model is that landscape is clockwise from portrait; thus on a
     * portrait device an app requesting landscape will cause a clockwise rotation, and on a
     * landscape device an app requesting portrait will cause a counter-clockwise rotation. Setting
     * true here reverses that logic. See go/natural-orientation for context.
     */
    val isReverseDefaultRotation: Boolean

    /** Provides the current rear display state. */
    val isInRearDisplayMode: StateFlow<Boolean>

    /** Provides the current display rotation */
    val currentRotation: StateFlow<DisplayRotation>

    /** Provides the current display size */
    val currentDisplaySize: StateFlow<Size>
}

// TODO(b/296211844): This class could directly use DeviceStateRepository and DisplayRepository
// instead.
@SysUISingleton
class DisplayStateRepositoryImpl
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    @Application val context: Context,
    deviceStateManager: DeviceStateManager,
    displayManager: DisplayManager,
    @Background backgroundHandler: Handler,
    @Background backgroundExecutor: Executor,
    @Background backgroundDispatcher: CoroutineDispatcher,
) : DisplayStateRepository {
    override val isReverseDefaultRotation =
        context.resources.getBoolean(com.android.internal.R.bool.config_reverseDefaultRotation)

    override val isInRearDisplayMode: StateFlow<Boolean> =
        conflatedCallbackFlow {
                val sendRearDisplayStateUpdate = { state: Boolean ->
                    trySendWithFailureLogging(
                        state,
                        TAG,
                        "Error sending rear display state update to $state"
                    )
                }

                val callback =
                    DeviceStateManager.DeviceStateCallback { state ->
                        val isInRearDisplayMode =
                            ArrayUtils.contains(
                                context.resources.getIntArray(
                                    com.android.internal.R.array.config_rearDisplayDeviceStates
                                ),
                                state
                            )
                        sendRearDisplayStateUpdate(isInRearDisplayMode)
                    }

                sendRearDisplayStateUpdate(false)
                deviceStateManager.registerCallback(backgroundExecutor, callback)
                awaitClose { deviceStateManager.unregisterCallback(callback) }
            }
            .flowOn(backgroundDispatcher)
            .stateIn(
                applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    private fun getDisplayInfo(): DisplayInfo {
        val cachedDisplayInfo = DisplayInfo()
        context.display?.getDisplayInfo(cachedDisplayInfo)
        return cachedDisplayInfo
    }

    private val currentDisplayInfo: StateFlow<DisplayInfo> =
        conflatedCallbackFlow {
                val callback =
                    object : DisplayListener {
                        override fun onDisplayRemoved(displayId: Int) {}

                        override fun onDisplayAdded(displayId: Int) {}

                        override fun onDisplayChanged(displayId: Int) {
                            traceSection(
                                "DisplayStateRepository" +
                                    ".currentRotationDisplayListener#onDisplayChanged"
                            ) {
                                val displayInfo = getDisplayInfo()
                                trySendWithFailureLogging(
                                    displayInfo,
                                    TAG,
                                    "Error sending displayInfo to $displayInfo"
                                )
                            }
                        }
                    }
                displayManager.registerDisplayListener(
                    callback,
                    backgroundHandler,
                    EVENT_FLAG_DISPLAY_CHANGED
                )
                awaitClose { displayManager.unregisterDisplayListener(callback) }
            }
            .flowOn(backgroundDispatcher)
            .stateIn(
                applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = getDisplayInfo(),
            )

    private fun rotationToDisplayRotation(rotation: Int): DisplayRotation {
        var adjustedRotation = rotation
        if (isReverseDefaultRotation) {
            adjustedRotation = (rotation + 1) % 4
        }
        return adjustedRotation.toDisplayRotation()
    }

    override val currentRotation: StateFlow<DisplayRotation> =
        currentDisplayInfo
            .map { rotationToDisplayRotation(it.rotation) }
            .stateIn(
                applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = rotationToDisplayRotation(currentDisplayInfo.value.rotation)
            )

    override val currentDisplaySize: StateFlow<Size> =
        currentDisplayInfo
            .map { Size(it.naturalWidth, it.naturalHeight) }
            .stateIn(
                applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    Size(
                        currentDisplayInfo.value.naturalWidth,
                        currentDisplayInfo.value.naturalHeight
                    ),
            )

    companion object {
        const val TAG = "DisplayStateRepositoryImpl"
    }
}

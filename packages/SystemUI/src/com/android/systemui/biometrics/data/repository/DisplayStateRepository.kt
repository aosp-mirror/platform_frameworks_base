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
import android.util.DisplayMetrics
import android.util.Size
import android.view.DisplayInfo
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.toDisplayRotation
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DeviceStateRepository
import com.android.systemui.display.data.repository.DeviceStateRepository.DeviceState.REAR_DISPLAY
import com.android.systemui.display.data.repository.DisplayRepository
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    /** Provides whether the current display is large screen */
    val isLargeScreen: StateFlow<Boolean>
}

@SysUISingleton
class DisplayStateRepositoryImpl
@Inject
constructor(
    @Background backgroundScope: CoroutineScope,
    @Application val context: Context,
    deviceStateRepository: DeviceStateRepository,
    displayRepository: DisplayRepository,
) : DisplayStateRepository {
    override val isReverseDefaultRotation =
        context.resources.getBoolean(com.android.internal.R.bool.config_reverseDefaultRotation)

    override val isInRearDisplayMode: StateFlow<Boolean> =
        deviceStateRepository.state
            .map { it == REAR_DISPLAY }
            .stateIn(
                backgroundScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    private fun getDisplayInfo(): DisplayInfo {
        val cachedDisplayInfo = DisplayInfo()
        context.display?.getDisplayInfo(cachedDisplayInfo)
        return cachedDisplayInfo
    }

    private val currentDisplayInfo: StateFlow<DisplayInfo> =
        displayRepository.displayChangeEvent
            .map { getDisplayInfo() }
            .stateIn(
                backgroundScope,
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
                backgroundScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = rotationToDisplayRotation(currentDisplayInfo.value.rotation)
            )

    override val currentDisplaySize: StateFlow<Size> =
        currentDisplayInfo
            .map { Size(it.naturalWidth, it.naturalHeight) }
            .stateIn(
                backgroundScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    Size(
                        currentDisplayInfo.value.naturalWidth,
                        currentDisplayInfo.value.naturalHeight
                    ),
            )

    override val isLargeScreen: StateFlow<Boolean> =
        currentDisplayInfo
            .map {
                // copied from systemui/shared/...Utilities.java
                val smallestWidth =
                    dpiFromPx(
                        min(it.logicalWidth, it.logicalHeight).toFloat(),
                        context.resources.configuration.densityDpi
                    )
                smallestWidth >= LARGE_SCREEN_MIN_DPS
            }
            .stateIn(
                backgroundScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    private fun dpiFromPx(size: Float, densityDpi: Int): Float {
        val densityRatio = densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT
        return size / densityRatio
    }

    companion object {
        const val TAG = "DisplayStateRepositoryImpl"
        const val LARGE_SCREEN_MIN_DPS = 600f
    }
}

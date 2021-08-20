/*
 * Copyright (C) 2021 The Android Open Source Project
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
@file:JvmName("UnfoldTransitionFactory")

package com.android.systemui.unfold

import android.content.Context
import android.hardware.SensorManager
import android.hardware.devicestate.DeviceStateManager
import android.os.Handler
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider
import com.android.systemui.unfold.config.ANIMATION_MODE_HINGE_ANGLE
import com.android.systemui.unfold.config.ResourceUnfoldTransitionConfig
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.progress.FixedTimingTransitionProgressProvider
import com.android.systemui.unfold.progress.PhysicsBasedUnfoldTransitionProgressProvider
import com.android.systemui.unfold.updates.DeviceFoldStateProvider
import com.android.systemui.unfold.updates.hinge.EmptyHingeAngleProvider
import com.android.systemui.unfold.updates.hinge.RotationSensorHingeAngleProvider
import java.lang.IllegalStateException
import java.util.concurrent.Executor

fun createUnfoldTransitionProgressProvider(
    context: Context,
    config: UnfoldTransitionConfig,
    screenStatusProvider: ScreenStatusProvider,
    deviceStateManager: DeviceStateManager,
    sensorManager: SensorManager,
    mainHandler: Handler,
    mainExecutor: Executor
): UnfoldTransitionProgressProvider {

    if (!config.isEnabled) {
        throw IllegalStateException("Trying to create " +
            "UnfoldTransitionProgressProvider when the transition is disabled")
    }

    val hingeAngleProvider =
        if (config.mode == ANIMATION_MODE_HINGE_ANGLE) {
            RotationSensorHingeAngleProvider(sensorManager)
        } else {
            EmptyHingeAngleProvider()
        }

    val foldStateProvider = DeviceFoldStateProvider(
        context,
        hingeAngleProvider,
        screenStatusProvider,
        deviceStateManager,
        mainExecutor
    )

    return if (config.mode == ANIMATION_MODE_HINGE_ANGLE) {
        PhysicsBasedUnfoldTransitionProgressProvider(
            mainHandler,
            foldStateProvider
        )
    } else {
        FixedTimingTransitionProgressProvider(foldStateProvider)
    }
}

fun createConfig(context: Context): UnfoldTransitionConfig =
    ResourceUnfoldTransitionConfig(context)

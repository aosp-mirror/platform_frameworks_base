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

import android.app.ActivityManager
import android.content.Context
import android.hardware.SensorManager
import android.hardware.devicestate.DeviceStateManager
import android.os.Handler
import com.android.systemui.unfold.config.ResourceUnfoldTransitionConfig
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider
import java.util.concurrent.Executor

/**
 * Factory for [UnfoldTransitionProgressProvider].
 *
 * This is needed as Launcher has to create the object manually. If dagger is available, this object
 * is provided in [UnfoldSharedModule].
 *
 * This should **never** be called from sysui, as the object is already provided in that process.
 */
fun createUnfoldTransitionProgressProvider(
    context: Context,
    config: UnfoldTransitionConfig,
    screenStatusProvider: ScreenStatusProvider,
    deviceStateManager: DeviceStateManager,
    activityManager: ActivityManager,
    sensorManager: SensorManager,
    mainHandler: Handler,
    mainExecutor: Executor,
    backgroundExecutor: Executor,
    tracingTagPrefix: String
): UnfoldTransitionProgressProvider =
    DaggerUnfoldSharedComponent.factory()
        .create(
            context,
            config,
            screenStatusProvider,
            deviceStateManager,
            activityManager,
            sensorManager,
            mainHandler,
            mainExecutor,
            backgroundExecutor,
            tracingTagPrefix)
        .unfoldTransitionProvider
        .orElse(null)
        ?: throw IllegalStateException(
            "Trying to create " +
                "UnfoldTransitionProgressProvider when the transition is disabled")

fun createConfig(context: Context): UnfoldTransitionConfig = ResourceUnfoldTransitionConfig(context)

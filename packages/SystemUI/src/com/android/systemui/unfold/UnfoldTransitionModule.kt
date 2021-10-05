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

package com.android.systemui.unfold

import android.content.Context
import android.hardware.SensorManager
import android.hardware.devicestate.DeviceStateManager
import android.os.Handler
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.LifecycleScreenStatusProvider
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider
import dagger.Lazy
import dagger.Module
import dagger.Provides
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Singleton

@Module
class UnfoldTransitionModule {

    @Provides
    @Singleton
    fun provideUnfoldTransitionProgressProvider(
        context: Context,
        config: UnfoldTransitionConfig,
        screenStatusProvider: LifecycleScreenStatusProvider,
        deviceStateManager: DeviceStateManager,
        sensorManager: SensorManager,
        @Main executor: Executor,
        @Main handler: Handler
    ): UnfoldTransitionProgressProvider =
        createUnfoldTransitionProgressProvider(
            context,
            config,
            screenStatusProvider,
            deviceStateManager,
            sensorManager,
            handler,
            executor
        )

    @Provides
    @Singleton
    fun provideUnfoldTransitionConfig(context: Context): UnfoldTransitionConfig =
        createConfig(context)

    @Provides
    @Singleton
    fun provideShellProgressProvider(
        config: UnfoldTransitionConfig,
        provider: Lazy<UnfoldTransitionProgressProvider>
    ): Optional<ShellUnfoldProgressProvider> =
        if (config.isEnabled) {
            Optional.ofNullable(ShellUnfoldProgressProvider(provider.get()))
        } else {
            Optional.empty()
        }
}

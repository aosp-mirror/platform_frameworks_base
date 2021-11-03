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
import android.view.IWindowManager
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.LifecycleScreenStatusProvider
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.util.NaturalRotationUnfoldProgressProvider
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider
import dagger.Lazy
import dagger.Module
import dagger.Provides
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Named
import javax.inject.Singleton

@Module
class UnfoldTransitionModule {

    @Provides
    @Singleton
    fun provideUnfoldTransitionProgressProvider(
        context: Context,
        config: UnfoldTransitionConfig,
        screenStatusProvider: Lazy<LifecycleScreenStatusProvider>,
        deviceStateManager: DeviceStateManager,
        sensorManager: SensorManager,
        @Main executor: Executor,
        @Main handler: Handler
    ) =
        if (config.isEnabled) {
            Optional.of(
                createUnfoldTransitionProgressProvider(
                    context,
                    config,
                    screenStatusProvider.get(),
                    deviceStateManager,
                    sensorManager,
                    handler,
                    executor
                )
            )
        } else {
            Optional.empty()
        }

    @Provides
    @Singleton
    fun provideUnfoldTransitionConfig(context: Context): UnfoldTransitionConfig =
        createConfig(context)

    @Provides
    @Singleton
    fun provideNaturalRotationProgressProvider(
        context: Context,
        windowManager: IWindowManager,
        unfoldTransitionProgressProvider: Optional<UnfoldTransitionProgressProvider>
    ) =
        unfoldTransitionProgressProvider.map {
            provider -> NaturalRotationUnfoldProgressProvider(
                context,
                windowManager,
                provider
            )
        }

    @Provides
    @Named(UNFOLD_STATUS_BAR)
    @Singleton
    fun provideStatusBarScopedTransitionProvider(
        source: Optional<NaturalRotationUnfoldProgressProvider>
    ) =
        source.map {
            provider -> ScopedUnfoldTransitionProgressProvider(provider)
        }

    @Provides
    @Singleton
    fun provideShellProgressProvider(
        config: UnfoldTransitionConfig,
        provider: Optional<UnfoldTransitionProgressProvider>
    ): Optional<ShellUnfoldProgressProvider> =
        if (config.isEnabled && provider.isPresent()) {
            Optional.of(UnfoldProgressProvider(provider.get()))
        } else {
            Optional.empty()
        }
}

const val UNFOLD_STATUS_BAR = "unfold_status_bar"

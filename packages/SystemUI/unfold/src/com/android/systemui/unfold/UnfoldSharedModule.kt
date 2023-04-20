/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.hardware.SensorManager
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.dagger.UnfoldBackground
import com.android.systemui.unfold.progress.FixedTimingTransitionProgressProvider
import com.android.systemui.unfold.progress.PhysicsBasedUnfoldTransitionProgressProvider
import com.android.systemui.unfold.updates.DeviceFoldStateProvider
import com.android.systemui.unfold.updates.FoldStateProvider
import com.android.systemui.unfold.updates.hinge.EmptyHingeAngleProvider
import com.android.systemui.unfold.updates.hinge.HingeAngleProvider
import com.android.systemui.unfold.updates.hinge.HingeSensorAngleProvider
import com.android.systemui.unfold.util.ATraceLoggerTransitionProgressListener
import com.android.systemui.unfold.util.ScaleAwareTransitionProgressProvider
import com.android.systemui.unfold.util.UnfoldKeyguardVisibilityManager
import com.android.systemui.unfold.util.UnfoldKeyguardVisibilityManagerImpl
import com.android.systemui.unfold.util.UnfoldKeyguardVisibilityProvider
import dagger.Module
import dagger.Provides
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Singleton

@Module
class UnfoldSharedModule {
    @Provides
    @Singleton
    fun unfoldTransitionProgressProvider(
        config: UnfoldTransitionConfig,
        scaleAwareProviderFactory: ScaleAwareTransitionProgressProvider.Factory,
        tracingListener: ATraceLoggerTransitionProgressListener,
        foldStateProvider: FoldStateProvider
    ): Optional<UnfoldTransitionProgressProvider> =
        if (!config.isEnabled) {
            Optional.empty()
        } else {
            val baseProgressProvider =
                if (config.isHingeAngleEnabled) {
                    PhysicsBasedUnfoldTransitionProgressProvider(foldStateProvider)
                } else {
                    FixedTimingTransitionProgressProvider(foldStateProvider)
                }
            Optional.of(
                scaleAwareProviderFactory.wrap(baseProgressProvider).apply {
                    // Always present callback that logs animation beginning and end.
                    addCallback(tracingListener)
                }
            )
        }

    @Provides
    @Singleton
    fun provideFoldStateProvider(
        deviceFoldStateProvider: DeviceFoldStateProvider
    ): FoldStateProvider = deviceFoldStateProvider

    @Provides
    fun hingeAngleProvider(
        config: UnfoldTransitionConfig,
        sensorManager: SensorManager,
        @UnfoldBackground executor: Executor
    ): HingeAngleProvider =
        if (config.isHingeAngleEnabled) {
            HingeSensorAngleProvider(sensorManager, executor)
        } else {
            EmptyHingeAngleProvider
        }

    @Provides
    @Singleton
    fun unfoldKeyguardVisibilityProvider(
        impl: UnfoldKeyguardVisibilityManagerImpl
    ): UnfoldKeyguardVisibilityProvider = impl

    @Provides
    @Singleton
    fun unfoldKeyguardVisibilityManager(
        impl: UnfoldKeyguardVisibilityManagerImpl
    ): UnfoldKeyguardVisibilityManager = impl
}

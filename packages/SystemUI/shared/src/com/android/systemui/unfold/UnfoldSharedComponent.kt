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

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.hardware.SensorManager
import android.hardware.devicestate.DeviceStateManager
import android.os.Handler
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider
import com.android.systemui.unfold.util.UnfoldTransitionATracePrefix
import dagger.BindsInstance
import dagger.Component
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Singleton

/**
 * Provides [UnfoldTransitionProgressProvider]. The [Optional] is empty when the transition
 * animation is disabled.
 *
 * This component is meant to be used for places that don't use dagger. By providing those
 * parameters to the factory, all dagger objects are correctly instantiated. See
 * [createUnfoldTransitionProgressProvider] for an example.
 */
@Singleton
@Component(modules = [UnfoldSharedModule::class])
internal interface UnfoldSharedComponent {

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance context: Context,
            @BindsInstance config: UnfoldTransitionConfig,
            @BindsInstance screenStatusProvider: ScreenStatusProvider,
            @BindsInstance deviceStateManager: DeviceStateManager,
            @BindsInstance activityManager: ActivityManager,
            @BindsInstance sensorManager: SensorManager,
            @BindsInstance @Main handler: Handler,
            @BindsInstance @Main executor: Executor,
            @BindsInstance @UiBackground backgroundExecutor: Executor,
            @BindsInstance @UnfoldTransitionATracePrefix tracingTagPrefix: String,
            @BindsInstance contentResolver: ContentResolver = context.contentResolver
        ): UnfoldSharedComponent
    }

    val unfoldTransitionProvider: Optional<UnfoldTransitionProgressProvider>
}

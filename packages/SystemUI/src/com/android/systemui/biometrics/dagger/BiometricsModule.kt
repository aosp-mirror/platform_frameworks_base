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

package com.android.systemui.biometrics.dagger

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.concurrency.ThreadFactory
import dagger.Module
import dagger.Provides
import java.util.concurrent.Executor
import javax.inject.Qualifier

/**
 * Dagger module for all things biometric.
 */
@Module
object BiometricsModule {

    /** Background [Executor] for HAL related operations. */
    @Provides
    @SysUISingleton
    @JvmStatic
    @BiometricsBackground
    fun providesPluginExecutor(threadFactory: ThreadFactory): Executor =
        threadFactory.buildExecutorOnNewThread("biometrics")
}

/**
 * Background executor for HAL operations that are latency sensitive but too
 * slow to run on the main thread. Prefer the shared executors, such as
 * [com.android.systemui.dagger.qualifiers.Background] when a HAL is not directly involved.
 */
@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class BiometricsBackground

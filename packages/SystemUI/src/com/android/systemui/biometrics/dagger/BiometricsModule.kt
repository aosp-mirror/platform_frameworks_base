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

import android.content.res.Resources
import com.android.internal.R
import com.android.systemui.CoreStartable
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.EllipseOverlapDetectorParams
import com.android.systemui.biometrics.UdfpsUtils
import com.android.systemui.biometrics.data.repository.BiometricStatusRepository
import com.android.systemui.biometrics.data.repository.BiometricStatusRepositoryImpl
import com.android.systemui.biometrics.data.repository.DisplayStateRepository
import com.android.systemui.biometrics.data.repository.DisplayStateRepositoryImpl
import com.android.systemui.biometrics.data.repository.FacePropertyRepository
import com.android.systemui.biometrics.data.repository.FacePropertyRepositoryImpl
import com.android.systemui.biometrics.data.repository.FaceSettingsRepository
import com.android.systemui.biometrics.data.repository.FaceSettingsRepositoryImpl
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepositoryImpl
import com.android.systemui.biometrics.data.repository.PromptRepository
import com.android.systemui.biometrics.data.repository.PromptRepositoryImpl
import com.android.systemui.biometrics.udfps.BoundingBoxOverlapDetector
import com.android.systemui.biometrics.udfps.EllipseOverlapDetector
import com.android.systemui.biometrics.udfps.OverlapDetector
import com.android.systemui.biometrics.ui.binder.SideFpsOverlayViewBinder
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import com.android.systemui.util.concurrency.ThreadFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import java.util.concurrent.Executor
import javax.inject.Qualifier

/** Dagger module for all things biometric. */
@Module
interface BiometricsModule {
    /** Starts AuthController.  */
    @Binds
    @IntoMap
    @ClassKey(AuthController::class)
    fun bindAuthControllerStartable(service: AuthController): CoreStartable

    /** Listen to config changes for AuthController. */
    @Binds
    @IntoSet
    fun bindAuthControllerConfigChanges(service: AuthController): ConfigurationListener

    @Binds
    @IntoMap
    @ClassKey(SideFpsOverlayViewBinder::class)
    fun bindsSideFpsOverlayViewBinder(viewBinder: SideFpsOverlayViewBinder): CoreStartable

    @Binds
    @SysUISingleton
    fun faceSettings(impl: FaceSettingsRepositoryImpl): FaceSettingsRepository

    @Binds @SysUISingleton fun faceSensors(impl: FacePropertyRepositoryImpl): FacePropertyRepository

    @Binds
    @SysUISingleton
    fun biometricPromptRepository(impl: PromptRepositoryImpl): PromptRepository

    @Binds
    @SysUISingleton
    fun biometricStatusRepository(impl: BiometricStatusRepositoryImpl): BiometricStatusRepository

    @Binds
    @SysUISingleton
    fun fingerprintRepository(
        impl: FingerprintPropertyRepositoryImpl
    ): FingerprintPropertyRepository

    @Binds
    @SysUISingleton
    fun displayStateRepository(impl: DisplayStateRepositoryImpl): DisplayStateRepository

    companion object {
        /** Background [Executor] for HAL related operations. */
        @Provides
        @SysUISingleton
        @JvmStatic
        @BiometricsBackground
        fun providesPluginExecutor(threadFactory: ThreadFactory): Executor =
            threadFactory.buildExecutorOnNewThread("biometrics")

        @Provides fun providesUdfpsUtils(): UdfpsUtils = UdfpsUtils()

        @Provides
        @SysUISingleton
        fun providesOverlapDetector(): OverlapDetector {
            val selectedOption =
                Resources.getSystem().getInteger(R.integer.config_selected_udfps_touch_detection)
            val values =
                Resources.getSystem()
                    .getStringArray(R.array.config_udfps_touch_detection_options)[selectedOption]
                    .split(",")
                    .map { it.toFloat() }

            return if (values[0] == 1f) {
                EllipseOverlapDetector(
                    EllipseOverlapDetectorParams(
                        minOverlap = values[3],
                        targetSize = values[2],
                        stepSize = values[4].toInt()
                    )
                )
            } else {
                BoundingBoxOverlapDetector(values[2])
            }
        }
    }
}

/**
 * Background executor for HAL operations that are latency sensitive but too slow to run on the main
 * thread. Prefer the shared executors, such as [com.android.systemui.dagger.qualifiers.Background]
 * when a HAL is not directly involved.
 */
@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class BiometricsBackground

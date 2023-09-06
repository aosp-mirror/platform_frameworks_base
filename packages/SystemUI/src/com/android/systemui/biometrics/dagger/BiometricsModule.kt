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

import com.android.settingslib.udfps.UdfpsUtils
import com.android.systemui.biometrics.data.repository.FacePropertyRepository
import com.android.systemui.biometrics.data.repository.FacePropertyRepositoryImpl
import com.android.systemui.biometrics.data.repository.FaceSettingsRepository
import com.android.systemui.biometrics.data.repository.FaceSettingsRepositoryImpl
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepositoryImpl
import com.android.systemui.biometrics.data.repository.PromptRepository
import com.android.systemui.biometrics.data.repository.PromptRepositoryImpl
import com.android.systemui.biometrics.data.repository.DisplayStateRepository
import com.android.systemui.biometrics.data.repository.DisplayStateRepositoryImpl
import com.android.systemui.biometrics.domain.interactor.CredentialInteractor
import com.android.systemui.biometrics.domain.interactor.CredentialInteractorImpl
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractorImpl
import com.android.systemui.biometrics.domain.interactor.LogContextInteractor
import com.android.systemui.biometrics.domain.interactor.LogContextInteractorImpl
import com.android.systemui.biometrics.domain.interactor.SideFpsOverlayInteractor
import com.android.systemui.biometrics.domain.interactor.SideFpsOverlayInteractorImpl
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractorImpl
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.concurrency.ThreadFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import java.util.concurrent.Executor
import javax.inject.Qualifier

/** Dagger module for all things biometric. */
@Module
interface BiometricsModule {

    @Binds
    @SysUISingleton
    fun faceSettings(impl: FaceSettingsRepositoryImpl): FaceSettingsRepository

    @Binds
    @SysUISingleton
    fun faceSensors(impl: FacePropertyRepositoryImpl): FacePropertyRepository

    @Binds
    @SysUISingleton
    fun biometricPromptRepository(impl: PromptRepositoryImpl): PromptRepository

    @Binds
    @SysUISingleton
    fun fingerprintRepository(impl: FingerprintPropertyRepositoryImpl):
            FingerprintPropertyRepository

    @Binds
    @SysUISingleton
    fun displayStateRepository(impl: DisplayStateRepositoryImpl): DisplayStateRepository

    @Binds
    @SysUISingleton
    fun providesPromptSelectorInteractor(impl: PromptSelectorInteractorImpl):
            PromptSelectorInteractor

    @Binds
    @SysUISingleton
    fun providesCredentialInteractor(impl: CredentialInteractorImpl): CredentialInteractor

    @Binds
    @SysUISingleton
    fun providesDisplayStateInteractor(impl: DisplayStateInteractorImpl): DisplayStateInteractor

    @Binds
    @SysUISingleton
    fun bindsLogContextInteractor(impl: LogContextInteractorImpl): LogContextInteractor

    @Binds
    @SysUISingleton
    fun providesSideFpsOverlayInteractor(impl: SideFpsOverlayInteractorImpl):
            SideFpsOverlayInteractor

    companion object {
        /** Background [Executor] for HAL related operations. */
        @Provides
        @SysUISingleton
        @JvmStatic
        @BiometricsBackground
        fun providesPluginExecutor(threadFactory: ThreadFactory): Executor =
            threadFactory.buildExecutorOnNewThread("biometrics")

        @Provides
        fun providesUdfpsUtils(): UdfpsUtils = UdfpsUtils()
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

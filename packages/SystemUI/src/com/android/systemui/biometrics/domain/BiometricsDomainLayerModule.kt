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
package com.android.systemui.biometrics.domain

import com.android.systemui.biometrics.domain.interactor.BiometricStatusInteractor
import com.android.systemui.biometrics.domain.interactor.BiometricStatusInteractorImpl
import com.android.systemui.biometrics.domain.interactor.CredentialInteractor
import com.android.systemui.biometrics.domain.interactor.CredentialInteractorImpl
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractorImpl
import com.android.systemui.biometrics.domain.interactor.LogContextInteractor
import com.android.systemui.biometrics.domain.interactor.LogContextInteractorImpl
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractorImpl
import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module

@Module
interface BiometricsDomainLayerModule {

    @Binds
    @SysUISingleton
    fun providesPromptSelectorInteractor(
        impl: PromptSelectorInteractorImpl
    ): PromptSelectorInteractor

    @Binds
    @SysUISingleton
    fun providesBiometricStatusInteractor(
        impl: BiometricStatusInteractorImpl
    ): BiometricStatusInteractor

    @Binds
    @SysUISingleton
    fun providesCredentialInteractor(impl: CredentialInteractorImpl): CredentialInteractor

    @Binds
    @SysUISingleton
    fun providesDisplayStateInteractor(impl: DisplayStateInteractorImpl): DisplayStateInteractor

    @Binds
    @SysUISingleton
    fun bindsLogContextInteractor(impl: LogContextInteractorImpl): LogContextInteractor
}

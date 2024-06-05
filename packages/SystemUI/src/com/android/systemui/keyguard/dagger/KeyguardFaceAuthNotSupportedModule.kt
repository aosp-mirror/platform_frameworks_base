/*
 *   Copyright (C) 2023 The Android Open Source Project
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.android.systemui.keyguard.dagger

import com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepository
import com.android.systemui.deviceentry.data.repository.NoopDeviceEntryFaceAuthRepository
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.NoopDeviceEntryFaceAuthInteractor
import dagger.Binds
import dagger.Module

/**
 * Module that provides bindings for face auth classes that are injected into SysUI components that
 * are used across different SysUI variants, where face auth is not supported.
 *
 * Some variants that do not support face authentication can install this module to provide a no-op
 * implementation of the interactor.
 */
@Module
interface KeyguardFaceAuthNotSupportedModule {
    @Binds
    fun keyguardFaceAuthInteractor(
        impl: NoopDeviceEntryFaceAuthInteractor
    ): DeviceEntryFaceAuthInteractor

    @Binds
    fun deviceEntryFaceAuthRepository(
        impl: NoopDeviceEntryFaceAuthRepository
    ): DeviceEntryFaceAuthRepository
}

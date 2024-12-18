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

package com.android.systemui.keyguard.data.repository

import com.android.systemui.CoreStartable
import com.android.systemui.bouncer.data.repository.BouncerMessageRepository
import com.android.systemui.bouncer.data.repository.BouncerMessageRepositoryImpl
import com.android.systemui.bouncer.data.repository.KeyguardBouncerRepository
import com.android.systemui.bouncer.data.repository.KeyguardBouncerRepositoryImpl
import com.android.systemui.bouncer.domain.interactor.BouncerMessageAuditLogger
import com.android.systemui.keyguard.ui.binder.SideFpsProgressBarViewBinder
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
interface KeyguardRepositoryModule {
    @Binds fun keyguardRepository(impl: KeyguardRepositoryImpl): KeyguardRepository

    @Binds
    @IntoMap
    @ClassKey(SideFpsProgressBarViewBinder::class)
    fun bindSideFpsProgressBarViewBinder(viewBinder: SideFpsProgressBarViewBinder): CoreStartable

    @Binds
    fun keyguardSurfaceBehindRepository(
        impl: KeyguardSurfaceBehindRepositoryImpl
    ): KeyguardSurfaceBehindRepository

    @Binds
    fun keyguardTransitionRepository(
        impl: KeyguardTransitionRepositoryImpl
    ): KeyguardTransitionRepository

    @Binds
    fun lightRevealScrimRepository(impl: LightRevealScrimRepositoryImpl): LightRevealScrimRepository

    @Binds fun devicePostureRepository(impl: DevicePostureRepositoryImpl): DevicePostureRepository

    @Binds
    fun biometricSettingsRepository(
        impl: BiometricSettingsRepositoryImpl
    ): BiometricSettingsRepository

    @Binds
    fun deviceEntryFingerprintAuthRepository(
        impl: DeviceEntryFingerprintAuthRepositoryImpl
    ): DeviceEntryFingerprintAuthRepository

    @Binds
    fun keyguardBouncerRepository(impl: KeyguardBouncerRepositoryImpl): KeyguardBouncerRepository

    @Binds
    fun bouncerMessageRepository(impl: BouncerMessageRepositoryImpl): BouncerMessageRepository

    @Binds
    @IntoMap
    @ClassKey(BouncerMessageAuditLogger::class)
    fun bind(impl: BouncerMessageAuditLogger): CoreStartable

    @Binds fun trustRepository(impl: TrustRepositoryImpl): TrustRepository

    @Binds fun keyguardClockRepository(impl: KeyguardClockRepositoryImpl): KeyguardClockRepository

    @Binds
    fun keyguardSmartspaceRepository(
        impl: KeyguardSmartspaceRepositoryImpl
    ): KeyguardSmartspaceRepository
}

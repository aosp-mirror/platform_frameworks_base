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
 * limitations under the License
 */

package com.android.systemui.keyguard.domain.interactor

import android.content.Context
import android.os.Handler
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.ui.BouncerView
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFingerprintAuthInteractor
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeTrustRepository
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.time.FakeSystemClock
import kotlinx.coroutines.test.TestScope
import org.mockito.Mockito.mock

/**
 * Helper to create a new KeyguardDismissInteractor in a way that doesn't require modifying many
 * tests whenever we add a constructor param.
 */
object KeyguardDismissInteractorFactory {
    @JvmOverloads
    @JvmStatic
    fun create(
        context: Context,
        testScope: TestScope,
        trustRepository: FakeTrustRepository = FakeTrustRepository(),
        keyguardRepository: FakeKeyguardRepository = FakeKeyguardRepository(),
        bouncerRepository: FakeKeyguardBouncerRepository = FakeKeyguardBouncerRepository(),
        keyguardUpdateMonitor: KeyguardUpdateMonitor = mock(KeyguardUpdateMonitor::class.java),
        powerRepository: FakePowerRepository = FakePowerRepository(),
        userRepository: FakeUserRepository = FakeUserRepository(),
    ): WithDependencies {
        val primaryBouncerInteractor =
            PrimaryBouncerInteractor(
                bouncerRepository,
                mock(BouncerView::class.java),
                mock(Handler::class.java),
                mock(KeyguardStateController::class.java),
                mock(KeyguardSecurityModel::class.java),
                mock(PrimaryBouncerCallbackInteractor::class.java),
                mock(FalsingCollector::class.java),
                mock(DismissCallbackRegistry::class.java),
                context,
                keyguardUpdateMonitor,
                trustRepository,
                testScope.backgroundScope,
                mock(SelectedUserInteractor::class.java),
                mock(DeviceEntryFaceAuthInteractor::class.java),
            )
        val alternateBouncerInteractor =
            AlternateBouncerInteractor(
                mock(StatusBarStateController::class.java),
                mock(KeyguardStateController::class.java),
                bouncerRepository,
                FakeFingerprintPropertyRepository(),
                FakeBiometricSettingsRepository(),
                FakeSystemClock(),
                keyguardUpdateMonitor,
                { mock(DeviceEntryFingerprintAuthInteractor::class.java) },
                { mock(KeyguardInteractor::class.java) },
                { mock(KeyguardTransitionInteractor::class.java) },
                { mock(SceneInteractor::class.java) },
                testScope.backgroundScope,
            )
        val powerInteractorWithDeps =
            PowerInteractorFactory.create(
                repository = powerRepository,
            )
        val selectedUserInteractor = SelectedUserInteractor(repository = userRepository)
        return WithDependencies(
            trustRepository = trustRepository,
            keyguardRepository = keyguardRepository,
            bouncerRepository = bouncerRepository,
            keyguardUpdateMonitor = keyguardUpdateMonitor,
            powerRepository = powerRepository,
            userRepository = userRepository,
            interactor =
                KeyguardDismissInteractor(
                    trustRepository,
                    keyguardRepository,
                    primaryBouncerInteractor,
                    alternateBouncerInteractor,
                    powerInteractorWithDeps.powerInteractor,
                    selectedUserInteractor,
                ),
        )
    }

    data class WithDependencies(
        val trustRepository: FakeTrustRepository,
        val keyguardRepository: FakeKeyguardRepository,
        val bouncerRepository: FakeKeyguardBouncerRepository,
        val keyguardUpdateMonitor: KeyguardUpdateMonitor,
        val powerRepository: FakePowerRepository,
        val userRepository: FakeUserRepository,
        val interactor: KeyguardDismissInteractor,
    )
}

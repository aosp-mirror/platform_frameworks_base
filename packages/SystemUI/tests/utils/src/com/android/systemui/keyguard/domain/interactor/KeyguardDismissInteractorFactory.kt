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

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.UserManager
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.ui.BouncerView
import com.android.systemui.broadcast.FakeBroadcastDispatcher
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeTrustRepository
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.telephony.data.repository.FakeTelephonyRepository
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.interactor.GuestUserInteractor
import com.android.systemui.user.domain.interactor.HeadlessSystemUserMode
import com.android.systemui.user.domain.interactor.RefreshUsersScheduler
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.utils.UserRestrictionChecker
import kotlinx.coroutines.CoroutineDispatcher
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
        broadcastDispatcher: FakeBroadcastDispatcher,
        dispatcher: CoroutineDispatcher,
        trustRepository: FakeTrustRepository = FakeTrustRepository(),
        keyguardRepository: FakeKeyguardRepository = FakeKeyguardRepository(),
        bouncerRepository: FakeKeyguardBouncerRepository = FakeKeyguardBouncerRepository(),
        keyguardUpdateMonitor: KeyguardUpdateMonitor = mock(KeyguardUpdateMonitor::class.java),
        featureFlags: FakeFeatureFlagsClassic =
            FakeFeatureFlagsClassic().apply {
                set(Flags.REFACTOR_KEYGUARD_DISMISS_INTENT, true)
                set(Flags.FULL_SCREEN_USER_SWITCHER, false)
            },
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
            )
        val alternateBouncerInteractor =
            AlternateBouncerInteractor(
                mock(StatusBarStateController::class.java),
                mock(KeyguardStateController::class.java),
                bouncerRepository,
                FakeBiometricSettingsRepository(),
                FakeSystemClock(),
                keyguardUpdateMonitor,
            )
        val powerInteractor =
            PowerInteractor(
                powerRepository,
                keyguardRepository,
                mock(FalsingCollector::class.java),
                mock(ScreenOffAnimationController::class.java),
                mock(StatusBarStateController::class.java),
            )
        val userInteractor =
            UserInteractor(
                applicationContext = context,
                repository = userRepository,
                mock(ActivityStarter::class.java),
                keyguardInteractor =
                    KeyguardInteractorFactory.create(
                            repository = keyguardRepository,
                            bouncerRepository = bouncerRepository,
                            featureFlags = featureFlags,
                        )
                        .keyguardInteractor,
                featureFlags = featureFlags,
                manager = mock(UserManager::class.java),
                headlessSystemUserMode = mock(HeadlessSystemUserMode::class.java),
                applicationScope = testScope.backgroundScope,
                telephonyInteractor =
                    TelephonyInteractor(
                        repository = FakeTelephonyRepository(),
                    ),
                broadcastDispatcher = broadcastDispatcher,
                keyguardUpdateMonitor = keyguardUpdateMonitor,
                backgroundDispatcher = dispatcher,
                activityManager = mock(ActivityManager::class.java),
                refreshUsersScheduler = mock(RefreshUsersScheduler::class.java),
                guestUserInteractor = mock(GuestUserInteractor::class.java),
                uiEventLogger = mock(UiEventLogger::class.java),
                userRestrictionChecker = mock(UserRestrictionChecker::class.java),
            )
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
                    powerInteractor,
                    userInteractor,
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

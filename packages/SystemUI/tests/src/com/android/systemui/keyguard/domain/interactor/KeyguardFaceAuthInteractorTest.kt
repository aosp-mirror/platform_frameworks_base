/*
 *  Copyright (C) 2023 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.os.Handler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.FaceAuthUiEvent
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dump.logcatLogBuffer
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.BouncerView
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.FakeTrustRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.log.FaceAuthenticationLogger
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardFaceAuthInteractorTest : SysuiTestCase() {

    private lateinit var underTest: SystemUIKeyguardFaceAuthInteractor
    private lateinit var testScope: TestScope
    private lateinit var bouncerRepository: FakeKeyguardBouncerRepository
    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository
    private lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor
    private lateinit var faceAuthRepository: FakeDeviceEntryFaceAuthRepository

    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        testScope = TestScope(dispatcher)
        val featureFlags = FakeFeatureFlags()
        featureFlags.set(Flags.FACE_AUTH_REFACTOR, true)
        bouncerRepository = FakeKeyguardBouncerRepository()
        faceAuthRepository = FakeDeviceEntryFaceAuthRepository()
        keyguardTransitionRepository = FakeKeyguardTransitionRepository()
        keyguardTransitionInteractor =
            KeyguardTransitionInteractor(keyguardTransitionRepository, testScope.backgroundScope)

        underTest =
            SystemUIKeyguardFaceAuthInteractor(
                testScope.backgroundScope,
                dispatcher,
                faceAuthRepository,
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
                    FakeTrustRepository(),
                    FakeFeatureFlags().apply { set(Flags.DELAY_BOUNCER, true) },
                    testScope.backgroundScope,
                ),
                AlternateBouncerInteractor(
                    mock(StatusBarStateController::class.java),
                    mock(KeyguardStateController::class.java),
                    bouncerRepository,
                    mock(BiometricSettingsRepository::class.java),
                    FakeDeviceEntryFingerprintAuthRepository(),
                    FakeSystemClock(),
                ),
                keyguardTransitionInteractor,
                featureFlags,
                FaceAuthenticationLogger(logcatLogBuffer("faceAuthBuffer")),
                keyguardUpdateMonitor,
            )
    }

    @Test
    fun faceAuthIsRequestedWhenLockscreenBecomesVisibleFromOffState() =
        testScope.runTest {
            underTest.start()

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.OFF,
                    KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED
                )
            )

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(FaceAuthUiEvent.FACE_AUTH_UPDATED_KEYGUARD_VISIBILITY_CHANGED, true)
                )
        }

    @Test
    fun faceAuthIsRequestedWhenLockscreenBecomesVisibleFromAodState() =
        testScope.runTest {
            underTest.start()

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.AOD,
                    KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED
                )
            )

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(FaceAuthUiEvent.FACE_AUTH_UPDATED_KEYGUARD_VISIBILITY_CHANGED, true)
                )
        }

    @Test
    fun faceAuthIsRequestedWhenLockscreenBecomesVisibleFromDozingState() =
        testScope.runTest {
            underTest.start()

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.DOZING,
                    KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED
                )
            )

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(FaceAuthUiEvent.FACE_AUTH_UPDATED_KEYGUARD_VISIBILITY_CHANGED, true)
                )
        }

    @Test
    fun faceAuthIsRequestedWhenPrimaryBouncerIsVisible() =
        testScope.runTest {
            underTest.start()

            bouncerRepository.setPrimaryShow(false)
            runCurrent()

            bouncerRepository.setPrimaryShow(true)

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(Pair(FaceAuthUiEvent.FACE_AUTH_UPDATED_PRIMARY_BOUNCER_SHOWN, true))
        }

    @Test
    fun faceAuthIsRequestedWhenAlternateBouncerIsVisible() =
        testScope.runTest {
            underTest.start()

            bouncerRepository.setAlternateVisible(false)
            runCurrent()

            bouncerRepository.setAlternateVisible(true)

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(
                        FaceAuthUiEvent.FACE_AUTH_TRIGGERED_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN,
                        false
                    )
                )
        }

    @Test
    fun faceAuthIsRequestedWhenUdfpsSensorTouched() =
        testScope.runTest {
            underTest.start()

            underTest.onUdfpsSensorTouched()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(Pair(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_UDFPS_POINTER_DOWN, false))
        }

    @Test
    fun faceAuthIsRequestedWhenOnAssistantTriggeredOnLockScreen() =
        testScope.runTest {
            underTest.start()

            underTest.onAssistantTriggeredOnLockScreen()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(FaceAuthUiEvent.FACE_AUTH_UPDATED_ASSISTANT_VISIBILITY_CHANGED, true)
                )
        }

    @Test
    fun faceAuthIsRequestedWhenDeviceLifted() =
        testScope.runTest {
            underTest.start()

            underTest.onDeviceLifted()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_PICK_UP_GESTURE_TRIGGERED, true)
                )
        }

    @Test
    fun faceAuthIsRequestedWhenQsExpansionStared() =
        testScope.runTest {
            underTest.start()

            underTest.onQsExpansionStared()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(Pair(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_QS_EXPANDED, true))
        }

    @Test
    fun faceAuthIsRequestedWhenNotificationPanelClicked() =
        testScope.runTest {
            underTest.start()

            underTest.onNotificationPanelClicked()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_NOTIFICATION_PANEL_CLICKED, true)
                )
        }

    @Test
    fun faceAuthIsCancelledWhenUserInputOnPrimaryBouncer() =
        testScope.runTest {
            underTest.start()

            underTest.onSwipeUpOnBouncer()

            runCurrent()
            assertThat(faceAuthRepository.isAuthRunning.value).isTrue()

            underTest.onPrimaryBouncerUserInput()

            runCurrent()

            assertThat(faceAuthRepository.isAuthRunning.value).isFalse()
        }

    @Test
    fun faceAuthIsRequestedWhenSwipeUpOnBouncer() =
        testScope.runTest {
            underTest.start()

            underTest.onSwipeUpOnBouncer()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(Pair(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER, false))
        }
}

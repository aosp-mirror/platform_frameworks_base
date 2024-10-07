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

package com.android.systemui.keyguard.domain.interactor

import android.os.Handler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.ui.BouncerView
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryBiometricsAllowedInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.FakeTrustRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.testKosmos
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntrySideFpsOverlayInteractorTest : SysuiTestCase() {
    @JvmField @Rule var mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var faceAuthInteractor: DeviceEntryFaceAuthInteractor
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var mSelectedUserInteractor: SelectedUserInteractor

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val bouncerRepository = FakeKeyguardBouncerRepository()
    private val biometricSettingsRepository = FakeBiometricSettingsRepository()
    private val deviceEntryFingerprintAuthRepository = FakeDeviceEntryFingerprintAuthRepository()

    private lateinit var primaryBouncerInteractor: PrimaryBouncerInteractor
    private lateinit var alternateBouncerInteractor: AlternateBouncerInteractor

    private lateinit var underTest: DeviceEntrySideFpsOverlayInteractor

    @Before
    fun setup() {
        primaryBouncerInteractor =
            PrimaryBouncerInteractor(
                bouncerRepository,
                mock(BouncerView::class.java),
                mock(Handler::class.java),
                mock(KeyguardStateController::class.java),
                mock(KeyguardSecurityModel::class.java),
                mock(PrimaryBouncerCallbackInteractor::class.java),
                mock(FalsingCollector::class.java),
                mock(DismissCallbackRegistry::class.java),
                mContext,
                keyguardUpdateMonitor,
                FakeTrustRepository(),
                testScope.backgroundScope,
                mSelectedUserInteractor,
                faceAuthInteractor
            )

        alternateBouncerInteractor =
            AlternateBouncerInteractor(
                mock(StatusBarStateController::class.java),
                mock(KeyguardStateController::class.java),
                bouncerRepository,
                FakeFingerprintPropertyRepository(),
                biometricSettingsRepository,
                FakeSystemClock(),
                keyguardUpdateMonitor,
                { mock(DeviceEntryBiometricsAllowedInteractor::class.java) },
                { mock(KeyguardInteractor::class.java) },
                { mock(KeyguardTransitionInteractor::class.java) },
                { kosmos.sceneInteractor },
                testScope.backgroundScope,
            )

        underTest =
            DeviceEntrySideFpsOverlayInteractor(
                testScope.backgroundScope,
                mContext,
                deviceEntryFingerprintAuthRepository,
                kosmos.sceneInteractor,
                primaryBouncerInteractor,
                alternateBouncerInteractor,
                keyguardUpdateMonitor
            )
    }

    @Test
    fun updatesShowIndicatorForDeviceEntry_onPrimaryBouncerShowing() =
        testScope.runTest {
            val showIndicatorForDeviceEntry by
                collectLastValue(underTest.showIndicatorForDeviceEntry)
            runCurrent()

            updatePrimaryBouncer(
                isShowing = true,
                isAnimatingAway = false,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )
            assertThat(showIndicatorForDeviceEntry).isTrue()
        }

    @Test
    fun updatesShowIndicatorForDeviceEntry_onPrimaryBouncerHidden() =
        testScope.runTest {
            val showIndicatorForDeviceEntry by
                collectLastValue(underTest.showIndicatorForDeviceEntry)
            runCurrent()

            updatePrimaryBouncer(
                isShowing = false,
                isAnimatingAway = false,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )
            assertThat(showIndicatorForDeviceEntry).isFalse()
        }

    @Test
    @EnableSceneContainer
    fun updatesShowIndicatorForDeviceEntry_onBouncerSceneActive() =
        testScope.runTest {
            underTest =
                DeviceEntrySideFpsOverlayInteractor(
                    testScope.backgroundScope,
                    mContext,
                    deviceEntryFingerprintAuthRepository,
                    kosmos.sceneInteractor,
                    primaryBouncerInteractor,
                    alternateBouncerInteractor,
                    keyguardUpdateMonitor
                )

            val showIndicatorForDeviceEntry by
                collectLastValue(underTest.showIndicatorForDeviceEntry)
            runCurrent()

            updateBouncerScene(
                isActive = true,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )
            assertThat(showIndicatorForDeviceEntry).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun updatesShowIndicatorForDeviceEntry_onBouncerSceneInactive() =
        testScope.runTest {
            underTest =
                DeviceEntrySideFpsOverlayInteractor(
                    testScope.backgroundScope,
                    mContext,
                    deviceEntryFingerprintAuthRepository,
                    kosmos.sceneInteractor,
                    primaryBouncerInteractor,
                    alternateBouncerInteractor,
                    keyguardUpdateMonitor
                )

            val showIndicatorForDeviceEntry by
                collectLastValue(underTest.showIndicatorForDeviceEntry)
            runCurrent()

            updateBouncerScene(
                isActive = false,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )
            assertThat(showIndicatorForDeviceEntry).isFalse()
        }

    @Test
    fun updatesShowIndicatorForDeviceEntry_fromPrimaryBouncer_whenFpsDetectionNotRunning() {
        testScope.runTest {
            val showIndicatorForDeviceEntry by
                collectLastValue(underTest.showIndicatorForDeviceEntry)
            runCurrent()

            updatePrimaryBouncer(
                isShowing = true,
                isAnimatingAway = false,
                fpsDetectionRunning = false,
                isUnlockingWithFpAllowed = true
            )
            assertThat(showIndicatorForDeviceEntry).isFalse()
        }
    }

    @Test
    fun updatesShowIndicatorForDeviceEntry_fromPrimaryBouncer_onUnlockingWithFpDisallowed() {
        testScope.runTest {
            val showIndicatorForDeviceEntry by
                collectLastValue(underTest.showIndicatorForDeviceEntry)
            runCurrent()

            updatePrimaryBouncer(
                isShowing = true,
                isAnimatingAway = false,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = false
            )
            assertThat(showIndicatorForDeviceEntry).isFalse()
        }
    }

    @Test
    fun updatesShowIndicatorForDeviceEntry_fromBouncerScene_whenFpsDetectionNotRunning() {
        testScope.runTest {
            val showIndicatorForDeviceEntry by
                collectLastValue(underTest.showIndicatorForDeviceEntry)
            runCurrent()

            updateBouncerScene(
                isActive = true,
                fpsDetectionRunning = false,
                isUnlockingWithFpAllowed = true
            )
            assertThat(showIndicatorForDeviceEntry).isFalse()
        }
    }

    @Test
    fun updatesShowIndicatorForDeviceEntry_fromBouncerScene_onUnlockingWithFpDisallowed() {
        testScope.runTest {
            val showIndicatorForDeviceEntry by
                collectLastValue(underTest.showIndicatorForDeviceEntry)
            runCurrent()

            updateBouncerScene(
                isActive = true,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = false
            )
            assertThat(showIndicatorForDeviceEntry).isFalse()
        }
    }

    @Test
    fun updatesShowIndicatorForDeviceEntry_onPrimaryBouncerAnimatingAway() {
        testScope.runTest {
            val showIndicatorForDeviceEntry by
                collectLastValue(underTest.showIndicatorForDeviceEntry)
            runCurrent()

            updatePrimaryBouncer(
                isShowing = true,
                isAnimatingAway = true,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )
            assertThat(showIndicatorForDeviceEntry).isFalse()
        }
    }

    @Test
    fun updatesShowIndicatorForDeviceEntry_onAlternateBouncerRequest() =
        testScope.runTest {
            val showIndicatorForDeviceEntry by
                collectLastValue(underTest.showIndicatorForDeviceEntry)
            runCurrent()

            bouncerRepository.setAlternateVisible(true)
            assertThat(showIndicatorForDeviceEntry).isTrue()

            bouncerRepository.setAlternateVisible(false)
            assertThat(showIndicatorForDeviceEntry).isFalse()
        }

    @Test
    fun ignoresDuplicateRequestsToShowIndicatorForDeviceEntry() =
        testScope.runTest {
            val showIndicatorForDeviceEntry by collectValues(underTest.showIndicatorForDeviceEntry)
            runCurrent()

            // Request to show indicator for primary bouncer showing
            updatePrimaryBouncer(
                isShowing = true,
                isAnimatingAway = false,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )

            // Another request to show indicator for deviceEntryFingerprintAuthRepository update
            deviceEntryFingerprintAuthRepository.setShouldUpdateIndicatorVisibility(true)

            // Request to show indicator for alternate bouncer showing
            bouncerRepository.setAlternateVisible(true)

            // Ensure only one show request is sent
            assertThat(showIndicatorForDeviceEntry).containsExactly(false, true)
        }

    private fun updatePrimaryBouncer(
        isShowing: Boolean,
        isAnimatingAway: Boolean,
        fpsDetectionRunning: Boolean,
        isUnlockingWithFpAllowed: Boolean,
    ) {
        bouncerRepository.setPrimaryShow(isShowing)
        bouncerRepository.setPrimaryStartingToHide(false)
        val primaryStartDisappearAnimation = if (isAnimatingAway) Runnable {} else null
        bouncerRepository.setPrimaryStartDisappearAnimation(primaryStartDisappearAnimation)

        whenever(keyguardUpdateMonitor.isFingerprintDetectionRunning)
            .thenReturn(fpsDetectionRunning)
        whenever(keyguardUpdateMonitor.isUnlockingWithFingerprintAllowed)
            .thenReturn(isUnlockingWithFpAllowed)
        mContext.orCreateTestableResources.addOverride(
            R.bool.config_show_sidefps_hint_on_bouncer,
            true
        )
    }

    private fun TestScope.updateBouncerScene(
        isActive: Boolean,
        fpsDetectionRunning: Boolean,
        isUnlockingWithFpAllowed: Boolean,
    ) {
        kosmos.sceneInteractor.changeScene(
            if (isActive) Scenes.Bouncer else Scenes.Lockscreen,
            "reason"
        )

        whenever(keyguardUpdateMonitor.isFingerprintDetectionRunning)
            .thenReturn(fpsDetectionRunning)
        whenever(keyguardUpdateMonitor.isUnlockingWithFingerprintAllowed)
            .thenReturn(isUnlockingWithFpAllowed)
        mContext.orCreateTestableResources.addOverride(
            R.bool.config_show_sidefps_hint_on_bouncer,
            true
        )
        runCurrent()
    }
}

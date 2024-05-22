/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.data.repository.fakeAccessibilityRepository
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.AuthenticationResult
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel.Companion.UNLOCKED_DELAY_MS
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryIconViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var fingerprintPropertyRepository: FakeFingerprintPropertyRepository
    private lateinit var fingerprintAuthRepository: FakeDeviceEntryFingerprintAuthRepository
    private lateinit var deviceEntryIconTransition: FakeDeviceEntryIconTransition
    private lateinit var underTest: DeviceEntryIconViewModel

    @Before
    fun setUp() {
        keyguardRepository = kosmos.fakeKeyguardRepository
        fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
        fingerprintAuthRepository = kosmos.fakeDeviceEntryFingerprintAuthRepository
        deviceEntryIconTransition = kosmos.fakeDeviceEntryIconViewModelTransition
        underTest = kosmos.deviceEntryIconViewModel
    }

    @Test
    fun isLongPressEnabled_udfpsRunning() =
        testScope.runTest {
            val isLongPressEnabled by collectLastValue(underTest.isLongPressEnabled)
            setUpState(
                isUdfpsSupported = true,
                isUdfpsRunning = true,
            )
            assertThat(isLongPressEnabled).isFalse()
        }

    @Test
    fun isLongPressEnabled_unlocked() =
        testScope.runTest {
            val isLongPressEnabled by collectLastValue(underTest.isLongPressEnabled)
            setUpState(
                isUdfpsSupported = true,
                isLockscreenDismissible = true,
            )
            assertThat(isLongPressEnabled).isTrue()
        }

    @Test
    fun isLongPressEnabled_lock() =
        testScope.runTest {
            val isLongPressEnabled by collectLastValue(underTest.isLongPressEnabled)
            setUpState(isUdfpsSupported = true)

            // udfps supported
            assertThat(isLongPressEnabled).isTrue()

            // udfps isn't supported
            fingerprintPropertyRepository.supportsRearFps()
            assertThat(isLongPressEnabled).isFalse()
        }

    @Test
    fun isVisible() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            deviceEntryIconTransitionAlpha(1f)
            assertThat(isVisible).isTrue()

            deviceEntryIconTransitionAlpha(0f)
            assertThat(isVisible).isFalse()

            deviceEntryIconTransitionAlpha(.5f)
            assertThat(isVisible).isTrue()
        }

    @Test
    @DisableSceneContainer
    fun iconType_fingerprint() =
        testScope.runTest {
            val iconType by collectLastValue(underTest.iconType)
            setUpState(
                isUdfpsSupported = true,
                isUdfpsRunning = true,
            )
            assertThat(iconType).isEqualTo(DeviceEntryIconView.IconType.FINGERPRINT)
        }

    @Test
    @DisableSceneContainer
    fun iconType_locked() =
        testScope.runTest {
            val iconType by collectLastValue(underTest.iconType)
            setUpState()
            assertThat(iconType).isEqualTo(DeviceEntryIconView.IconType.LOCK)
        }

    @Test
    @DisableSceneContainer
    fun iconType_unlocked() =
        testScope.runTest {
            val iconType by collectLastValue(underTest.iconType)
            setUpState(isLockscreenDismissible = true)
            assertThat(iconType).isEqualTo(DeviceEntryIconView.IconType.UNLOCK)
        }

    @Test
    @DisableSceneContainer
    fun iconType_none() =
        testScope.runTest {
            val iconType by collectLastValue(underTest.iconType)
            setUpState(
                isUdfpsSupported = true,
                isUdfpsRunning = true,
                isLockscreenDismissible = true,
            )
            assertThat(iconType).isEqualTo(DeviceEntryIconView.IconType.NONE)
        }

    @Test
    @EnableSceneContainer
    fun iconType_fingerprint_withSceneContainer() =
        testScope.runTest {
            val iconType by collectLastValue(underTest.iconType)
            setUpState(
                isUdfpsSupported = true,
                isUdfpsRunning = true,
            )
            assertThat(iconType).isEqualTo(DeviceEntryIconView.IconType.FINGERPRINT)
        }

    @Test
    @EnableSceneContainer
    fun iconType_locked_withSceneContainer() =
        testScope.runTest {
            val iconType by collectLastValue(underTest.iconType)
            setUpState()
            assertThat(iconType).isEqualTo(DeviceEntryIconView.IconType.LOCK)
        }

    @Test
    @EnableSceneContainer
    fun iconType_unlocked_withSceneContainer() =
        testScope.runTest {
            val iconType by collectLastValue(underTest.iconType)
            setUpState(
                isLockscreenDismissible = true,
            )
            assertThat(iconType).isEqualTo(DeviceEntryIconView.IconType.UNLOCK)
        }

    @Test
    @EnableSceneContainer
    fun iconType_none_withSceneContainer() =
        testScope.runTest {
            val iconType by collectLastValue(underTest.iconType)
            setUpState(
                isUdfpsSupported = true,
                isUdfpsRunning = true,
                isLockscreenDismissible = true,
            )
            assertThat(iconType).isEqualTo(DeviceEntryIconView.IconType.NONE)
        }

    fun accessibilityDelegateHint_accessibilityNotEnabled() =
        testScope.runTest {
            val accessibilityDelegateHint by collectLastValue(underTest.accessibilityDelegateHint)
            kosmos.fakeAccessibilityRepository.isEnabled.value = false
            assertThat(accessibilityDelegateHint)
                .isEqualTo(DeviceEntryIconView.AccessibilityHintType.NONE)
        }

    @Test
    fun accessibilityDelegateHint_accessibilityEnabled_locked() =
        testScope.runTest {
            val accessibilityDelegateHint by collectLastValue(underTest.accessibilityDelegateHint)
            kosmos.fakeAccessibilityRepository.isEnabled.value = true

            // interactive lock icon
            setUpState(isUdfpsSupported = true)

            assertThat(accessibilityDelegateHint)
                .isEqualTo(DeviceEntryIconView.AccessibilityHintType.AUTHENTICATE)

            // non-interactive lock icon
            fingerprintPropertyRepository.supportsRearFps()

            assertThat(accessibilityDelegateHint)
                .isEqualTo(DeviceEntryIconView.AccessibilityHintType.NONE)
        }

    @Test
    fun accessibilityDelegateHint_accessibilityEnabled_unlocked() =
        testScope.runTest {
            val accessibilityDelegateHint by collectLastValue(underTest.accessibilityDelegateHint)
            kosmos.fakeAccessibilityRepository.isEnabled.value = true

            // interactive unlock icon
            setUpState(
                isUdfpsSupported = true,
                isLockscreenDismissible = true,
            )

            assertThat(accessibilityDelegateHint)
                .isEqualTo(DeviceEntryIconView.AccessibilityHintType.ENTER)
        }

    private fun deviceEntryIconTransitionAlpha(alpha: Float) {
        deviceEntryIconTransition.setDeviceEntryParentViewAlpha(alpha)
    }

    private suspend fun TestScope.setUpState(
        isUdfpsSupported: Boolean = false,
        isUdfpsRunning: Boolean = false,
        isLockscreenDismissible: Boolean = false,
    ) {
        if (isUdfpsSupported) {
            fingerprintPropertyRepository.supportsUdfps()
        }
        if (isUdfpsRunning) {
            check(isUdfpsSupported) { "Cannot set UDFPS as running if it's not supported!" }
            fingerprintAuthRepository.setIsRunning(true)
        } else {
            fingerprintAuthRepository.setIsRunning(false)
        }
        if (isLockscreenDismissible) {
            setLockscreenDismissible()
        } else {
            if (!SceneContainerFlag.isEnabled) {
                keyguardRepository.setKeyguardDismissible(false)
            }
        }
        runCurrent()
    }

    private suspend fun TestScope.setLockscreenDismissible() {
        if (SceneContainerFlag.isEnabled) {
            // Need to set up a collection for the authentication to be propagated.
            val unused by collectLastValue(kosmos.deviceUnlockedInteractor.deviceUnlockStatus)
            runCurrent()
            assertThat(
                    kosmos.authenticationInteractor.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN
                    )
                )
                .isEqualTo(AuthenticationResult.SUCCEEDED)
        } else {
            keyguardRepository.setKeyguardDismissible(true)
        }
        advanceTimeBy(UNLOCKED_DELAY_MS * 2) // wait for unlocked delay
    }
}

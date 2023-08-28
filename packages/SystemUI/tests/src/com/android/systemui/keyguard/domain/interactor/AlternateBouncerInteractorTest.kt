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

package com.android.systemui.keyguard.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.RoboPilotTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.KeyguardBouncerRepository
import com.android.systemui.keyguard.data.repository.KeyguardBouncerRepositoryImpl
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.time.SystemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RoboPilotTest
@RunWith(AndroidJUnit4::class)
class AlternateBouncerInteractorTest : SysuiTestCase() {
    private lateinit var underTest: AlternateBouncerInteractor
    private lateinit var bouncerRepository: KeyguardBouncerRepository
    private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository
    private lateinit var deviceEntryFingerprintAuthRepository:
        FakeDeviceEntryFingerprintAuthRepository
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var systemClock: SystemClock
    @Mock private lateinit var bouncerLogger: TableLogBuffer

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        bouncerRepository =
            KeyguardBouncerRepositoryImpl(
                FakeSystemClock(),
                TestCoroutineScope(),
                bouncerLogger,
            )
        biometricSettingsRepository = FakeBiometricSettingsRepository()
        deviceEntryFingerprintAuthRepository = FakeDeviceEntryFingerprintAuthRepository()
        underTest =
            AlternateBouncerInteractor(
                statusBarStateController,
                keyguardStateController,
                bouncerRepository,
                biometricSettingsRepository,
                deviceEntryFingerprintAuthRepository,
                systemClock,
            )
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_givenCanShow() {
        givenCanShowAlternateBouncer()
        assertTrue(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_alternateBouncerUIUnavailable() {
        givenCanShowAlternateBouncer()
        bouncerRepository.setAlternateBouncerUIAvailable(false)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_noFingerprintsEnrolled() {
        givenCanShowAlternateBouncer()
        biometricSettingsRepository.setFingerprintEnrolled(false)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_strongBiometricNotAllowed() {
        givenCanShowAlternateBouncer()
        biometricSettingsRepository.setStrongBiometricAllowed(false)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_devicePolicyDoesNotAllowFingerprint() {
        givenCanShowAlternateBouncer()
        biometricSettingsRepository.setFingerprintEnabledByDevicePolicy(false)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_fingerprintLockedOut() {
        givenCanShowAlternateBouncer()
        deviceEntryFingerprintAuthRepository.setLockedOut(true)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun show_whenCanShow() {
        givenCanShowAlternateBouncer()

        assertTrue(underTest.show())
        assertTrue(bouncerRepository.alternateBouncerVisible.value)
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_butCanDismissLockScreen() {
        givenCanShowAlternateBouncer()
        whenever(keyguardStateController.isUnlocked).thenReturn(true)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun show_whenCannotShow() {
        givenCannotShowAlternateBouncer()

        assertFalse(underTest.show())
        assertFalse(bouncerRepository.alternateBouncerVisible.value)
    }

    @Test
    fun hide_wasPreviouslyShowing() {
        bouncerRepository.setAlternateVisible(true)

        assertTrue(underTest.hide())
        assertFalse(bouncerRepository.alternateBouncerVisible.value)
    }

    @Test
    fun hide_wasNotPreviouslyShowing() {
        bouncerRepository.setAlternateVisible(false)

        assertFalse(underTest.hide())
        assertFalse(bouncerRepository.alternateBouncerVisible.value)
    }

    private fun givenCanShowAlternateBouncer() {
        bouncerRepository.setAlternateBouncerUIAvailable(true)
        biometricSettingsRepository.setFingerprintEnrolled(true)
        biometricSettingsRepository.setStrongBiometricAllowed(true)
        biometricSettingsRepository.setFingerprintEnabledByDevicePolicy(true)
        deviceEntryFingerprintAuthRepository.setLockedOut(false)
        whenever(keyguardStateController.isUnlocked).thenReturn(false)
    }

    private fun givenCannotShowAlternateBouncer() {
        biometricSettingsRepository.setFingerprintEnrolled(false)
    }
}

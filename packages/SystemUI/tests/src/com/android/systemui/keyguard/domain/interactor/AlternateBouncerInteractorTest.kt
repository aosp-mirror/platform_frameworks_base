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

import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.ViewMediatorCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeBiometricRepository
import com.android.systemui.keyguard.data.repository.KeyguardBouncerRepository
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.time.SystemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class AlternateBouncerInteractorTest : SysuiTestCase() {
    private lateinit var underTest: AlternateBouncerInteractor
    private lateinit var bouncerRepository: KeyguardBouncerRepository
    private lateinit var biometricRepository: FakeBiometricRepository
    @Mock private lateinit var systemClock: SystemClock
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var bouncerLogger: TableLogBuffer
    private lateinit var featureFlags: FakeFeatureFlags

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        bouncerRepository =
            KeyguardBouncerRepository(
                mock(ViewMediatorCallback::class.java),
                FakeSystemClock(),
                TestCoroutineScope(),
                bouncerLogger,
            )
        biometricRepository = FakeBiometricRepository()
        featureFlags = FakeFeatureFlags().apply { this.set(Flags.MODERN_ALTERNATE_BOUNCER, true) }
        underTest =
            AlternateBouncerInteractor(
                bouncerRepository,
                biometricRepository,
                systemClock,
                keyguardUpdateMonitor,
                featureFlags,
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
        biometricRepository.setFingerprintEnrolled(false)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_strongBiometricNotAllowed() {
        givenCanShowAlternateBouncer()
        biometricRepository.setStrongBiometricAllowed(false)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_devicePolicyDoesNotAllowFingerprint() {
        givenCanShowAlternateBouncer()
        biometricRepository.setFingerprintEnabledByDevicePolicy(false)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun show_whenCanShow() {
        givenCanShowAlternateBouncer()

        assertTrue(underTest.show())
        assertTrue(bouncerRepository.isAlternateBouncerVisible.value)
    }

    @Test
    fun show_whenCannotShow() {
        givenCannotShowAlternateBouncer()

        assertFalse(underTest.show())
        assertFalse(bouncerRepository.isAlternateBouncerVisible.value)
    }

    @Test
    fun hide_wasPreviouslyShowing() {
        bouncerRepository.setAlternateVisible(true)

        assertTrue(underTest.hide())
        assertFalse(bouncerRepository.isAlternateBouncerVisible.value)
    }

    @Test
    fun hide_wasNotPreviouslyShowing() {
        bouncerRepository.setAlternateVisible(false)

        assertFalse(underTest.hide())
        assertFalse(bouncerRepository.isAlternateBouncerVisible.value)
    }

    private fun givenCanShowAlternateBouncer() {
        bouncerRepository.setAlternateBouncerUIAvailable(true)
        biometricRepository.setFingerprintEnrolled(true)
        biometricRepository.setStrongBiometricAllowed(true)
        biometricRepository.setFingerprintEnabledByDevicePolicy(true)
    }

    private fun givenCannotShowAlternateBouncer() {
        biometricRepository.setFingerprintEnrolled(false)
    }
}

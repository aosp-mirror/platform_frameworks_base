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

package com.android.systemui.bouncer.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.bouncer.data.repository.KeyguardBouncerRepository
import com.android.systemui.bouncer.data.repository.KeyguardBouncerRepositoryImpl
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.time.SystemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AlternateBouncerInteractorTest : SysuiTestCase() {
    private lateinit var underTest: AlternateBouncerInteractor
    private lateinit var bouncerRepository: KeyguardBouncerRepository
    private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository
    private lateinit var fingerprintPropertyRepository: FakeFingerprintPropertyRepository
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var systemClock: SystemClock
    @Mock private lateinit var bouncerLogger: TableLogBuffer
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        bouncerRepository =
            KeyguardBouncerRepositoryImpl(
                FakeSystemClock(),
                TestScope().backgroundScope,
                bouncerLogger,
            )
        biometricSettingsRepository = FakeBiometricSettingsRepository()
        fingerprintPropertyRepository = FakeFingerprintPropertyRepository()

        mSetFlagsRule.disableFlags(Flags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
        initializeUnderTest()
    }

    private fun initializeUnderTest() {
        // Set any feature flags before creating the alternateBouncerInteractor
        underTest =
            AlternateBouncerInteractor(
                statusBarStateController,
                keyguardStateController,
                bouncerRepository,
                fingerprintPropertyRepository,
                biometricSettingsRepository,
                systemClock,
                keyguardUpdateMonitor,
                TestScope().backgroundScope,
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
    fun canShowAlternateBouncerForFingerprint_ifFingerprintIsNotUsuallyAllowed() {
        givenCanShowAlternateBouncer()
        biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_strongBiometricNotAllowed() {
        givenCanShowAlternateBouncer()
        biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(false)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_fingerprintLockedOut() {
        givenCanShowAlternateBouncer()
        whenever(keyguardUpdateMonitor.isFingerprintLockedOut).thenReturn(true)

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

    @Test
    @Ignore("b/287599719")
    fun canShowAlternateBouncerForFingerprint_rearFps() {
        mSetFlagsRule.enableFlags(Flags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
        initializeUnderTest()
        givenCanShowAlternateBouncer()
        fingerprintPropertyRepository.supportsRearFps() // does not support alternate bouncer

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun alternateBouncerUiAvailable_fromMultipleSources() {
        initializeUnderTest()
        assertFalse(bouncerRepository.alternateBouncerUIAvailable.value)

        // GIVEN there are two different sources indicating the alternate bouncer is available
        underTest.setAlternateBouncerUIAvailable(true, "source1")
        underTest.setAlternateBouncerUIAvailable(true, "source2")
        assertTrue(bouncerRepository.alternateBouncerUIAvailable.value)

        // WHEN one of the sources no longer says the UI is available
        underTest.setAlternateBouncerUIAvailable(false, "source1")

        // THEN alternate bouncer UI is still available (from the other source)
        assertTrue(bouncerRepository.alternateBouncerUIAvailable.value)

        // WHEN all sources say the UI is not available
        underTest.setAlternateBouncerUIAvailable(false, "source2")

        // THEN alternate boucer UI is not available
        assertFalse(bouncerRepository.alternateBouncerUIAvailable.value)
    }

    private fun givenCanShowAlternateBouncer() {
        if (DeviceEntryUdfpsRefactor.isEnabled) {
            fingerprintPropertyRepository.supportsUdfps()
        } else {
            bouncerRepository.setAlternateBouncerUIAvailable(true)
        }

        biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
        biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)
        whenever(keyguardUpdateMonitor.isFingerprintLockedOut).thenReturn(false)
        whenever(keyguardStateController.isUnlocked).thenReturn(false)
    }

    private fun givenCannotShowAlternateBouncer() {
        biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
    }
}

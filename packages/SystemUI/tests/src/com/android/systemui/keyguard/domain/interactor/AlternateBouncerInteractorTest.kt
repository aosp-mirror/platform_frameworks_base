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
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.ViewMediatorCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
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
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
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
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var bouncerLogger: TableLogBuffer
    private lateinit var featureFlags: FakeFeatureFlags

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        bouncerRepository =
            KeyguardBouncerRepositoryImpl(
                mock(ViewMediatorCallback::class.java),
                FakeSystemClock(),
                TestCoroutineScope(),
                bouncerLogger,
            )
        biometricSettingsRepository = FakeBiometricSettingsRepository()
        deviceEntryFingerprintAuthRepository = FakeDeviceEntryFingerprintAuthRepository()
        featureFlags = FakeFeatureFlags().apply { this.set(Flags.MODERN_ALTERNATE_BOUNCER, true) }
        underTest =
            AlternateBouncerInteractor(
                statusBarStateController,
                keyguardStateController,
                bouncerRepository,
                biometricSettingsRepository,
                deviceEntryFingerprintAuthRepository,
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
    fun canShowAlternateBouncerForFingerprint_isDozing() {
        givenCanShowAlternateBouncer()
        whenever(statusBarStateController.isDozing).thenReturn(true)

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
    fun onUnlockedIsFalse_doesNotHide() {
        // GIVEN alternate bouncer is showing
        bouncerRepository.setAlternateVisible(true)

        val keyguardStateControllerCallbackCaptor =
            ArgumentCaptor.forClass(KeyguardStateController.Callback::class.java)
        verify(keyguardStateController).addCallback(keyguardStateControllerCallbackCaptor.capture())

        // WHEN isUnlocked=false
        givenCanShowAlternateBouncer()
        whenever(keyguardStateController.isUnlocked).thenReturn(false)
        keyguardStateControllerCallbackCaptor.value.onUnlockedChanged()

        // THEN the alternate bouncer is still visible
        assertTrue(bouncerRepository.alternateBouncerVisible.value)
    }

    @Test
    fun onUnlockedChangedIsTrue_hide() {
        // GIVEN alternate bouncer is showing
        bouncerRepository.setAlternateVisible(true)

        val keyguardStateControllerCallbackCaptor =
            ArgumentCaptor.forClass(KeyguardStateController.Callback::class.java)
        verify(keyguardStateController).addCallback(keyguardStateControllerCallbackCaptor.capture())

        // WHEN isUnlocked=true
        givenCanShowAlternateBouncer()
        whenever(keyguardStateController.isUnlocked).thenReturn(true)
        keyguardStateControllerCallbackCaptor.value.onUnlockedChanged()

        // THEN the alternate bouncer is hidden
        assertFalse(bouncerRepository.alternateBouncerVisible.value)
    }

    private fun givenCanShowAlternateBouncer() {
        bouncerRepository.setAlternateBouncerUIAvailable(true)
        biometricSettingsRepository.setFingerprintEnrolled(true)
        biometricSettingsRepository.setStrongBiometricAllowed(true)
        biometricSettingsRepository.setFingerprintEnabledByDevicePolicy(true)
        deviceEntryFingerprintAuthRepository.setLockedOut(false)
        whenever(keyguardStateController.isUnlocked).thenReturn(false)
        whenever(statusBarStateController.isDozing).thenReturn(false)
    }

    private fun givenCannotShowAlternateBouncer() {
        biometricSettingsRepository.setFingerprintEnrolled(false)
    }
}

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
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.policy.keyguardStateController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class AlternateBouncerInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private lateinit var underTest: AlternateBouncerInteractor

    @Before
    fun setup() {
        underTest = kosmos.alternateBouncerInteractor
    }

    @Test
    @DisableSceneContainer
    fun canShowAlternateBouncer_false_dueToTransitionState() =
        kosmos.testScope.runTest {
            givenAlternateBouncerSupported()
            val canShowAlternateBouncer by collectLastValue(underTest.canShowAlternateBouncer)
            kosmos.fakeKeyguardTransitionRepository.sendTransitionStep(
                from = KeyguardState.AOD,
                to = KeyguardState.GONE,
                validateStep = false,
            )
            assertFalse(canShowAlternateBouncer!!)
        }

    @Test
    @EnableSceneContainer
    fun canShowAlternateBouncer_false_dueToTransitionState_scene_container() =
        kosmos.testScope.runTest {
            givenAlternateBouncerSupported()
            val canShowAlternateBouncer by collectLastValue(underTest.canShowAlternateBouncer)
            val isDeviceUnlocked by
                collectLastValue(
                    kosmos.deviceUnlockedInteractor.deviceUnlockStatus.map { it.isUnlocked }
                )
            assertThat(isDeviceUnlocked).isFalse()

            kosmos.authenticationInteractor.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)
            assertThat(isDeviceUnlocked).isTrue()
            kosmos.sceneInteractor.changeScene(Scenes.Gone, "")

            assertThat(canShowAlternateBouncer).isFalse()
        }

    @Test
    fun canShowAlternateBouncerForFingerprint_ifFingerprintIsNotUsuallyAllowed() {
        givenCanShowAlternateBouncer()
        kosmos.biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_strongBiometricNotAllowed() {
        givenCanShowAlternateBouncer()
        kosmos.biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(false)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_fingerprintLockedOut() {
        givenCanShowAlternateBouncer()
        whenever(kosmos.keyguardUpdateMonitor.isFingerprintLockedOut).thenReturn(true)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_butCanDismissLockScreen() {
        givenCanShowAlternateBouncer()
        whenever(kosmos.keyguardStateController.isUnlocked).thenReturn(true)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_primaryBouncerShowing() {
        givenCanShowAlternateBouncer()
        kosmos.keyguardBouncerRepository.setPrimaryShow(true)

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    @Test
    fun hide_wasPreviouslyShowing() {
        kosmos.keyguardBouncerRepository.setAlternateVisible(true)

        assertTrue(underTest.hide())
        assertFalse(kosmos.keyguardBouncerRepository.alternateBouncerVisible.value)
    }

    @Test
    fun hide_wasNotPreviouslyShowing() {
        kosmos.keyguardBouncerRepository.setAlternateVisible(false)

        assertFalse(underTest.hide())
        assertFalse(kosmos.keyguardBouncerRepository.alternateBouncerVisible.value)
    }

    @Test
    fun canShowAlternateBouncerForFingerprint_rearFps() {
        givenCanShowAlternateBouncer()
        kosmos.fingerprintPropertyRepository.supportsRearFps() // does not support alternate bouncer

        assertFalse(underTest.canShowAlternateBouncerForFingerprint())
    }

    private fun givenAlternateBouncerSupported() {
        kosmos.givenAlternateBouncerSupported()
    }

    private fun givenCanShowAlternateBouncer() {
        kosmos.givenCanShowAlternateBouncer()
    }
}

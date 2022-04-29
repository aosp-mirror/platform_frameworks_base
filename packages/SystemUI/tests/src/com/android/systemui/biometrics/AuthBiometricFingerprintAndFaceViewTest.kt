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

package com.android.systemui.biometrics

import android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE
import android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class AuthBiometricFingerprintAndFaceViewTest : SysuiTestCase() {

    @JvmField @Rule
    var mockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var callback: AuthBiometricView.Callback
    @Mock
    private lateinit var panelController: AuthPanelController

    private lateinit var biometricView: AuthBiometricFingerprintAndFaceView

    @Before
    fun setup() {
        biometricView = R.layout.auth_biometric_fingerprint_and_face_view
                .asTestAuthBiometricView(mContext, callback, panelController)
        waitForIdleSync()
    }

    @After
    fun tearDown() {
        biometricView.destroyDialog()
    }

    @Test
    fun fingerprintSuccessDoesNotRequireExplicitConfirmation() {
        biometricView.onDialogAnimatedIn()
        biometricView.onAuthenticationSucceeded(TYPE_FINGERPRINT)
        waitForIdleSync()

        assertThat(biometricView.isAuthenticated).isTrue()
        verify(callback).onAction(AuthBiometricView.Callback.ACTION_AUTHENTICATED)
    }

    @Test
    fun faceSuccessRequiresExplicitConfirmation() {
        biometricView.onDialogAnimatedIn()
        biometricView.onAuthenticationSucceeded(TYPE_FACE)
        waitForIdleSync()

        assertThat(biometricView.isAuthenticated).isFalse()
        assertThat(biometricView.isAuthenticating).isFalse()
        assertThat(biometricView.mConfirmButton.visibility).isEqualTo(View.GONE)
        verify(callback, never()).onAction(AuthBiometricView.Callback.ACTION_AUTHENTICATED)

        // icon acts as confirm button
        biometricView.mIconView.performClick()
        waitForIdleSync()

        assertThat(biometricView.isAuthenticated).isTrue()
        verify(callback).onAction(AuthBiometricView.Callback.ACTION_AUTHENTICATED)
    }

    @Test
    fun ignoresFaceErrors() {
        biometricView.onDialogAnimatedIn()
        biometricView.onError(TYPE_FACE, "not a face")
        waitForIdleSync()

        assertThat(biometricView.isAuthenticating).isTrue()
        verify(callback, never()).onAction(AuthBiometricView.Callback.ACTION_ERROR)

        biometricView.onError(TYPE_FINGERPRINT, "that's a nope")
        waitForIdleSync()

        verify(callback).onAction(AuthBiometricView.Callback.ACTION_ERROR)
    }

    override fun waitForIdleSync() = TestableLooper.get(this).processAllMessages()
}

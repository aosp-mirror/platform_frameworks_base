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

import android.hardware.biometrics.BiometricAuthenticator
import android.os.Bundle
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
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class AuthBiometricFingerprintViewTest : SysuiTestCase() {

    @JvmField
    @Rule
    val mockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var callback: AuthBiometricView.Callback

    @Mock
    private lateinit var panelController: AuthPanelController

    private lateinit var biometricView: AuthBiometricView

    private fun createView(allowDeviceCredential: Boolean = false): AuthBiometricFingerprintView {
        val view: AuthBiometricFingerprintView =
                R.layout.auth_biometric_fingerprint_view.asTestAuthBiometricView(
                mContext, callback, panelController, allowDeviceCredential = allowDeviceCredential
        )
        waitForIdleSync()
        return view
    }

    @Before
    fun setup() {
        biometricView = createView()
    }

    @After
    fun tearDown() {
        biometricView.destroyDialog()
    }

    @Test
    fun testOnAuthenticationSucceeded_noConfirmationRequired_sendsActionAuthenticated() {
        biometricView.onAuthenticationSucceeded(BiometricAuthenticator.TYPE_FINGERPRINT)
        TestableLooper.get(this).moveTimeForward(1000)
        waitForIdleSync()

        assertThat(biometricView.isAuthenticated).isTrue()
        verify(callback).onAction(AuthBiometricView.Callback.ACTION_AUTHENTICATED)
    }

    @Test
    fun testOnAuthenticationSucceeded_confirmationRequired_updatesDialogContents() {
        biometricView.setRequireConfirmation(true)
        biometricView.onAuthenticationSucceeded(BiometricAuthenticator.TYPE_FINGERPRINT)
        TestableLooper.get(this).moveTimeForward(1000)
        waitForIdleSync()

        // TODO: this should be tested in the subclasses
        if (biometricView.supportsRequireConfirmation()) {
            verify(callback, never()).onAction(ArgumentMatchers.anyInt())
            assertThat(biometricView.mNegativeButton.visibility).isEqualTo(View.GONE)
            assertThat(biometricView.mCancelButton.visibility).isEqualTo(View.VISIBLE)
            assertThat(biometricView.mCancelButton.isEnabled).isTrue()
            assertThat(biometricView.mConfirmButton.isEnabled).isTrue()
            assertThat(biometricView.mIndicatorView.text)
                    .isEqualTo(mContext.getText(R.string.biometric_dialog_tap_confirm))
            assertThat(biometricView.mIndicatorView.visibility).isEqualTo(View.VISIBLE)
        } else {
            assertThat(biometricView.isAuthenticated).isTrue()
            verify(callback).onAction(eq(AuthBiometricView.Callback.ACTION_AUTHENTICATED))
        }
    }

    @Test
    fun testPositiveButton_sendsActionAuthenticated() {
        biometricView.mConfirmButton.performClick()
        TestableLooper.get(this).moveTimeForward(1000)
        waitForIdleSync()

        verify(callback).onAction(AuthBiometricView.Callback.ACTION_AUTHENTICATED)
        assertThat(biometricView.isAuthenticated).isTrue()
    }

    @Test
    fun testNegativeButton_beforeAuthentication_sendsActionButtonNegative() {
        biometricView.onDialogAnimatedIn()
        biometricView.mNegativeButton.performClick()
        TestableLooper.get(this).moveTimeForward(1000)
        waitForIdleSync()

        verify(callback).onAction(AuthBiometricView.Callback.ACTION_BUTTON_NEGATIVE)
    }

    @Test
    fun testCancelButton_whenPendingConfirmation_sendsActionUserCanceled() {
        biometricView.setRequireConfirmation(true)
        biometricView.onAuthenticationSucceeded(BiometricAuthenticator.TYPE_FINGERPRINT)

        assertThat(biometricView.mNegativeButton.visibility).isEqualTo(View.GONE)
        biometricView.mCancelButton.performClick()
        TestableLooper.get(this).moveTimeForward(1000)
        waitForIdleSync()

        verify(callback).onAction(AuthBiometricView.Callback.ACTION_USER_CANCELED)
    }

    @Test
    fun testTryAgainButton_sendsActionTryAgain() {
        biometricView.mTryAgainButton.performClick()
        TestableLooper.get(this).moveTimeForward(1000)
        waitForIdleSync()

        verify(callback).onAction(AuthBiometricView.Callback.ACTION_BUTTON_TRY_AGAIN)
        assertThat(biometricView.mTryAgainButton.visibility).isEqualTo(View.GONE)
        assertThat(biometricView.isAuthenticating).isTrue()
    }

    @Test
    fun testOnErrorSendsActionError() {
        biometricView.onError(BiometricAuthenticator.TYPE_FACE, "testError")
        TestableLooper.get(this).moveTimeForward(1000)
        waitForIdleSync()

        verify(callback).onAction(eq(AuthBiometricView.Callback.ACTION_ERROR))
    }

    @Test
    fun testOnErrorShowsMessage() {
        // prevent error state from instantly returning to authenticating in the test
        biometricView.mAnimationDurationHideDialog = 10_000

        val message = "another error"
        biometricView.onError(BiometricAuthenticator.TYPE_FACE, message)
        TestableLooper.get(this).moveTimeForward(1000)
        waitForIdleSync()

        assertThat(biometricView.isAuthenticating).isFalse()
        assertThat(biometricView.isAuthenticated).isFalse()
        assertThat(biometricView.mIndicatorView.visibility).isEqualTo(View.VISIBLE)
        assertThat(biometricView.mIndicatorView.text).isEqualTo(message)
    }

    @Test
    fun testBackgroundClicked_sendsActionUserCanceled() {
        val view = View(mContext)
        biometricView.setBackgroundView(view)
        view.performClick()

        verify(callback).onAction(eq(AuthBiometricView.Callback.ACTION_USER_CANCELED))
    }

    @Test
    fun testBackgroundClicked_afterAuthenticated_neverSendsUserCanceled() {
        val view = View(mContext)
        biometricView.setBackgroundView(view)
        biometricView.onAuthenticationSucceeded(BiometricAuthenticator.TYPE_FINGERPRINT)
        waitForIdleSync()
        view.performClick()

        verify(callback, never())
                .onAction(eq(AuthBiometricView.Callback.ACTION_USER_CANCELED))
    }

    @Test
    fun testBackgroundClicked_whenSmallDialog_neverSendsUserCanceled() {
        biometricView.mLayoutParams = AuthDialog.LayoutParams(0, 0)
        biometricView.updateSize(AuthDialog.SIZE_SMALL)
        val view = View(mContext)
        biometricView.setBackgroundView(view)
        view.performClick()

        verify(callback, never()).onAction(eq(AuthBiometricView.Callback.ACTION_USER_CANCELED))
    }

    @Test
    fun testIgnoresUselessHelp() {
        biometricView.mAnimationDurationHideDialog = 10_000
        biometricView.onDialogAnimatedIn()
        waitForIdleSync()

        assertThat(biometricView.isAuthenticating).isTrue()

        val helpText = biometricView.mIndicatorView.text
        biometricView.onHelp(BiometricAuthenticator.TYPE_FINGERPRINT, "")
        waitForIdleSync()

        // text should not change
        assertThat(biometricView.mIndicatorView.text).isEqualTo(helpText)
        verify(callback, never()).onAction(eq(AuthBiometricView.Callback.ACTION_ERROR))
    }

    @Test
    fun testRestoresState() {
        val requireConfirmation = true
        biometricView.mAnimationDurationHideDialog = 10_000
        val failureMessage = "testFailureMessage"
        biometricView.setRequireConfirmation(requireConfirmation)
        biometricView.onAuthenticationFailed(BiometricAuthenticator.TYPE_FACE, failureMessage)
        waitForIdleSync()

        val state = Bundle()
        biometricView.onSaveState(state)
        assertThat(biometricView.mTryAgainButton.visibility).isEqualTo(View.GONE)
        assertThat(state.getInt(AuthDialog.KEY_BIOMETRIC_TRY_AGAIN_VISIBILITY))
                .isEqualTo(View.GONE)
        assertThat(state.getInt(AuthDialog.KEY_BIOMETRIC_STATE))
                .isEqualTo(AuthBiometricView.STATE_ERROR)
        assertThat(biometricView.mIndicatorView.visibility).isEqualTo(View.VISIBLE)
        assertThat(state.getBoolean(AuthDialog.KEY_BIOMETRIC_INDICATOR_ERROR_SHOWING)).isTrue()
        assertThat(biometricView.mIndicatorView.text).isEqualTo(failureMessage)
        assertThat(state.getString(AuthDialog.KEY_BIOMETRIC_INDICATOR_STRING))
                .isEqualTo(failureMessage)

        // TODO: Test dialog size. Should move requireConfirmation to buildBiometricPromptBundle

        // Create new dialog and restore the previous state into it
        biometricView.destroyDialog()
        biometricView = createView()
        biometricView.restoreState(state)
        biometricView.mAnimationDurationHideDialog = 10_000
        biometricView.setRequireConfirmation(requireConfirmation)
        waitForIdleSync()

        assertThat(biometricView.mTryAgainButton.visibility).isEqualTo(View.GONE)
        assertThat(biometricView.mIndicatorView.visibility).isEqualTo(View.VISIBLE)

        // TODO: Test restored text. Currently cannot test this, since it gets restored only after
        // dialog size is known.
    }

    @Test
    fun testCredentialButton_whenDeviceCredentialAllowed() {
        biometricView.destroyDialog()
        biometricView = createView(allowDeviceCredential = true)

        assertThat(biometricView.mUseCredentialButton.visibility).isEqualTo(View.VISIBLE)
        assertThat(biometricView.mNegativeButton.visibility).isEqualTo(View.GONE)

        biometricView.mUseCredentialButton.performClick()
        waitForIdleSync()

        verify(callback).onAction(AuthBiometricView.Callback.ACTION_USE_DEVICE_CREDENTIAL)
    }

    override fun waitForIdleSync() = TestableLooper.get(this).processAllMessages()
}

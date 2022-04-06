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

import android.app.admin.DevicePolicyManager
import android.hardware.biometrics.BiometricConstants
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.ComponentInfoInternal
import android.hardware.biometrics.PromptInfo
import android.hardware.biometrics.SensorProperties
import android.hardware.face.FaceSensorPropertiesInternal
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.os.Handler
import android.os.IBinder
import android.os.UserManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.testing.ViewUtils
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ScrollView
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidTestingRunner::class)
@RunWithLooper
@SmallTest
class AuthContainerViewTest : SysuiTestCase() {

    @JvmField @Rule
    var rule = MockitoJUnit.rule()

    @Mock
    lateinit var callback: AuthDialogCallback
    @Mock
    lateinit var userManager: UserManager
    @Mock
    lateinit var lockPatternUtils: LockPatternUtils
    @Mock
    lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock
    lateinit var windowToken: IBinder

    private var authContainer: TestAuthContainerView? = null

    @After
    fun tearDown() {
        if (authContainer?.isAttachedToWindow == true) {
            ViewUtils.detachView(authContainer)
        }
    }

    @Test
    fun testNotifiesAnimatedIn() {
        initializeContainer()
        verify(callback).onDialogAnimatedIn()
    }

    @Test
    fun testIgnoresAnimatedInWhenDismissed() {
        val container = initializeContainer(addToView = false)
        container.dismissFromSystemServer()
        waitForIdleSync()

        verify(callback, never()).onDialogAnimatedIn()

        container.addToView()
        waitForIdleSync()

        // attaching the view resets the state and allows this to happen again
        verify(callback).onDialogAnimatedIn()
    }

    @Test
    fun testActionAuthenticated_sendsDismissedAuthenticated() {
        val container = initializeContainer()
        container.mBiometricCallback.onAction(
            AuthBiometricView.Callback.ACTION_AUTHENTICATED
        )
        waitForIdleSync()

        verify(callback).onDismissed(
            eq(AuthDialogCallback.DISMISSED_BIOMETRIC_AUTHENTICATED),
            eq<ByteArray?>(null) /* credentialAttestation */
        )
        assertThat(container.parent).isNull()
    }

    @Test
    fun testActionUserCanceled_sendsDismissedUserCanceled() {
        val container = initializeContainer()
        container.mBiometricCallback.onAction(
            AuthBiometricView.Callback.ACTION_USER_CANCELED
        )
        waitForIdleSync()

        verify(callback).onSystemEvent(
            eq(BiometricConstants.BIOMETRIC_SYSTEM_EVENT_EARLY_USER_CANCEL)
        )
        verify(callback).onDismissed(
            eq(AuthDialogCallback.DISMISSED_USER_CANCELED),
            eq<ByteArray?>(null) /* credentialAttestation */
        )
        assertThat(container.parent).isNull()
    }

    @Test
    fun testActionButtonNegative_sendsDismissedButtonNegative() {
        val container = initializeContainer()
        container.mBiometricCallback.onAction(
            AuthBiometricView.Callback.ACTION_BUTTON_NEGATIVE
        )
        waitForIdleSync()

        verify(callback).onDismissed(
            eq(AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE),
            eq<ByteArray?>(null) /* credentialAttestation */
        )
        assertThat(container.parent).isNull()
    }

    @Test
    fun testActionTryAgain_sendsTryAgain() {
        val container = initializeContainer(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        container.mBiometricCallback.onAction(
            AuthBiometricView.Callback.ACTION_BUTTON_TRY_AGAIN
        )
        waitForIdleSync()

        verify(callback).onTryAgainPressed()
    }

    @Test
    fun testActionError_sendsDismissedError() {
        val container = initializeContainer()
        authContainer!!.mBiometricCallback.onAction(
            AuthBiometricView.Callback.ACTION_ERROR
        )
        waitForIdleSync()

        verify(callback).onDismissed(
            eq(AuthDialogCallback.DISMISSED_ERROR),
            eq<ByteArray?>(null) /* credentialAttestation */
        )
        assertThat(authContainer!!.parent).isNull()
    }

    @Test
    fun testActionUseDeviceCredential_sendsOnDeviceCredentialPressed() {
        val container = initializeContainer(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        container.mBiometricCallback.onAction(
            AuthBiometricView.Callback.ACTION_USE_DEVICE_CREDENTIAL
        )
        waitForIdleSync()

        verify(callback).onDeviceCredentialPressed()
        assertThat(container.hasCredentialView()).isTrue()
    }

    @Test
    fun testAnimateToCredentialUI_invokesStartTransitionToCredentialUI() {
        val container = initializeContainer(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        container.animateToCredentialUI()
        waitForIdleSync()

        assertThat(container.hasCredentialView()).isTrue()
    }

    @Test
    fun testShowBiometricUI() {
        val container = initializeContainer()

        waitForIdleSync()

        assertThat(container.hasCredentialView()).isFalse()
        assertThat(container.hasBiometricPrompt()).isTrue()
    }

    @Test
    fun testShowCredentialUI() {
        val container = initializeContainer(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        waitForIdleSync()

        assertThat(container.hasCredentialView()).isTrue()
        assertThat(container.hasBiometricPrompt()).isFalse()
    }

    @Test
    fun testCredentialViewUsesEffectiveUserId() {
        whenever(userManager.getCredentialOwnerProfile(anyInt())).thenReturn(200)
        whenever(lockPatternUtils.getKeyguardStoredPasswordQuality(eq(200))).thenReturn(
            DevicePolicyManager.PASSWORD_QUALITY_SOMETHING
        )

        val container = initializeContainer(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        waitForIdleSync()

        assertThat(container.hasCredentialPatternView()).isTrue()
        assertThat(container.hasBiometricPrompt()).isFalse()
    }

    @Test
    fun testCredentialUI_disablesClickingOnBackground() {
        whenever(userManager.getCredentialOwnerProfile(anyInt())).thenReturn(20)
        whenever(lockPatternUtils.getKeyguardStoredPasswordQuality(eq(20))).thenReturn(
            DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
        )

        // In the credential view, clicking on the background (to cancel authentication) is not
        // valid. Thus, the listener should be null, and it should not be in the accessibility
        // hierarchy.
        val container = initializeContainer(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        waitForIdleSync()

        assertThat(container.hasCredentialPasswordView()).isTrue()
        assertThat(container.hasBiometricPrompt()).isFalse()
        assertThat(
            container.findViewById<View>(R.id.background)?.isImportantForAccessibility
        ).isFalse()

        container.findViewById<View>(R.id.background)?.performClick()
        waitForIdleSync()

        assertThat(container.hasCredentialPasswordView()).isTrue()
        assertThat(container.hasBiometricPrompt()).isFalse()
    }

    @Test
    fun testLayoutParams_hasSecureWindowFlag() {
        val layoutParams = AuthContainerView.getLayoutParams(windowToken, "")
        assertThat((layoutParams.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0).isTrue()
    }

    @Test
    fun testLayoutParams_excludesImeInsets() {
        val layoutParams = AuthContainerView.getLayoutParams(windowToken, "")
        assertThat((layoutParams.fitInsetsTypes and WindowInsets.Type.ime()) == 0).isTrue()
    }

    private fun initializeContainer(
        authenticators: Int = BiometricManager.Authenticators.BIOMETRIC_WEAK,
        addToView: Boolean = true
    ): TestAuthContainerView {
        val config = AuthContainerView.Config()
        config.mContext = mContext
        config.mCallback = callback
        config.mSensorIds = intArrayOf(0)
        config.mSkipAnimation = true
        config.mPromptInfo = PromptInfo()
        config.mPromptInfo.authenticators = authenticators
        val componentInfo = listOf(
            ComponentInfoInternal(
                "faceSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */, "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */, "" /* softwareVersion */
            ),
            ComponentInfoInternal(
                "matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */, "" /* firmwareVersion */, "" /* serialNumber */,
                "vendor/version/revision" /* softwareVersion */
            )
        )
        val fpProps = listOf(
            FingerprintSensorPropertiesInternal(
                0,
                SensorProperties.STRENGTH_STRONG,
                5 /* maxEnrollmentsPerUser */,
                componentInfo,
                FingerprintSensorProperties.TYPE_REAR,
                false /* resetLockoutRequiresHardwareAuthToken */
            )
        )
        authContainer = TestAuthContainerView(
            config,
            fpProps,
            listOf(),
            wakefulnessLifecycle,
            userManager,
            lockPatternUtils,
            Handler(TestableLooper.get(this).looper)
        )

        if (addToView) {
            authContainer!!.addToView()
        }

        return authContainer!!
    }

    private inner class TestAuthContainerView(
        config: Config,
        fpProps: List<FingerprintSensorPropertiesInternal>,
        faceProps: List<FaceSensorPropertiesInternal>,
        wakefulnessLifecycle: WakefulnessLifecycle,
        userManager: UserManager,
        lockPatternUtils: LockPatternUtils,
        mainHandler: Handler
    ) : AuthContainerView(
        config, fpProps, faceProps,
        wakefulnessLifecycle, userManager, lockPatternUtils, mainHandler
    ) {
        override fun postOnAnimation(runnable: Runnable) {
            runnable.run()
        }
    }

    override fun waitForIdleSync() {
        TestableLooper.get(this).processAllMessages()
        super.waitForIdleSync()
    }

    private fun AuthContainerView.addToView() {
        ViewUtils.attachView(this)
        waitForIdleSync()
        assertThat(isAttachedToWindow).isTrue()
    }
}

private fun AuthContainerView.hasBiometricPrompt() =
    (findViewById<ScrollView>(R.id.biometric_scrollview)?.childCount ?: 0) > 0

private fun AuthContainerView.hasCredentialView() =
    hasCredentialPatternView() || hasCredentialPasswordView()

private fun AuthContainerView.hasCredentialPatternView() =
    findViewById<View>(R.id.lockPattern) != null

private fun AuthContainerView.hasCredentialPasswordView() =
    findViewById<View>(R.id.lockPassword) != null

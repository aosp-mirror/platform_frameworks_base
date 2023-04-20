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

import android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.PromptInfo
import android.hardware.face.FaceSensorPropertiesInternal
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.os.Handler
import android.os.IBinder
import android.os.UserManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import androidx.test.filters.SmallTest
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternView
import com.android.internal.widget.VerifyCredentialResponse
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthContainerView.BiometricCallback
import com.android.systemui.biometrics.AuthCredentialView.ErrorTimer
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class AuthCredentialViewTest : SysuiTestCase() {
    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock lateinit var callback: AuthDialogCallback
    @Mock lateinit var lockPatternUtils: LockPatternUtils
    @Mock lateinit var userManager: UserManager
    @Mock lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock lateinit var windowToken: IBinder
    @Mock lateinit var interactionJankMonitor: InteractionJankMonitor

    private var authContainer: TestAuthContainerView? = null
    private var authCredentialView: AuthCredentialPatternView? = null
    private var lockPatternView: LockPatternView? = null
    private var biometricCallback: BiometricCallback? = null
    private var errorTimer: ErrorTimer? = null

    @After
    fun tearDown() {
        if (authContainer?.isAttachedToWindow == true) {
            ViewUtils.detachView(authContainer)
        }
    }

    @Test
    fun testAuthCredentialPatternView_onErrorTimeoutFinish_setPatternEnabled() {
        `when`(lockPatternUtils.getCredentialTypeForUser(anyInt()))
            .thenReturn(LockPatternUtils.CREDENTIAL_TYPE_PATTERN)
        `when`(lockPatternUtils.getKeyguardStoredPasswordQuality(anyInt()))
            .thenReturn(PASSWORD_QUALITY_SOMETHING)
        val errorResponse: VerifyCredentialResponse = VerifyCredentialResponse.fromError()

        assertThat(initializeFingerprintContainer()).isNotNull()
        authContainer?.animateToCredentialUI()
        waitForIdleSync()

        authCredentialView = spy(authContainer?.mCredentialView as AuthCredentialPatternView)
        authCredentialView?.onCredentialVerified(errorResponse, 5000)
        errorTimer = authCredentialView?.mErrorTimer
        errorTimer?.onFinish()
        waitForIdleSync()

        verify(authCredentialView)?.onErrorTimeoutFinish()

        lockPatternView = authCredentialView?.mLockPatternView
        assertThat(lockPatternView?.isEnabled).isTrue()
    }

    private fun initializeFingerprintContainer(
        authenticators: Int = BiometricManager.Authenticators.BIOMETRIC_WEAK,
        addToView: Boolean = true
    ) =
        initializeContainer(
            TestAuthContainerView(
                authenticators = authenticators,
                fingerprintProps = fingerprintSensorPropertiesInternal()
            ),
            addToView
        )

    private fun initializeContainer(
        view: TestAuthContainerView,
        addToView: Boolean
    ): TestAuthContainerView {
        authContainer = view
        if (addToView) {
            authContainer!!.addToView()
            biometricCallback = authContainer?.mBiometricCallback
        }
        return authContainer!!
    }

    private inner class TestAuthContainerView(
        authenticators: Int = BiometricManager.Authenticators.BIOMETRIC_WEAK,
        fingerprintProps: List<FingerprintSensorPropertiesInternal> = listOf(),
        faceProps: List<FaceSensorPropertiesInternal> = listOf()
    ) :
        AuthContainerView(
            Config().apply {
                mContext = context
                mCallback = callback
                mSensorIds =
                    (fingerprintProps.map { it.sensorId } + faceProps.map { it.sensorId })
                        .toIntArray()
                mSkipAnimation = true
                mPromptInfo = PromptInfo().apply { this.authenticators = authenticators }
            },
            fingerprintProps,
            faceProps,
            wakefulnessLifecycle,
            userManager,
            lockPatternUtils,
            interactionJankMonitor,
            Handler(TestableLooper.get(this).looper),
            FakeExecutor(FakeSystemClock())
        ) {
        override fun postOnAnimation(runnable: Runnable) {
            runnable.run()
        }
    }

    override fun waitForIdleSync() = TestableLooper.get(this).processAllMessages()

    private fun AuthContainerView.addToView() {
        ViewUtils.attachView(this)
        waitForIdleSync()
        assertThat(isAttachedToWindow).isTrue()
    }
}

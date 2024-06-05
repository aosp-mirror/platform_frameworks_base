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

import android.app.ActivityTaskManager
import android.app.admin.DevicePolicyManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.biometrics.BiometricAuthenticator
import android.hardware.biometrics.BiometricConstants
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.Flags.FLAG_CUSTOM_BIOMETRIC_PROMPT
import android.hardware.biometrics.PromptContentViewWithMoreOptionsButton
import android.hardware.biometrics.PromptInfo
import android.hardware.biometrics.PromptVerticalListContentView
import android.hardware.face.FaceSensorPropertiesInternal
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.os.Handler
import android.os.IBinder
import android.os.UserManager
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.testing.ViewUtils
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ScrollView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.widget.LockPatternUtils
import com.android.launcher3.icons.IconProvider
import com.android.systemui.Flags.FLAG_CONSTRAINT_BP
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeBiometricStatusRepository
import com.android.systemui.biometrics.data.repository.FakeDisplayStateRepository
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.FakePromptRepository
import com.android.systemui.biometrics.domain.interactor.BiometricStatusInteractor
import com.android.systemui.biometrics.domain.interactor.BiometricStatusInteractorImpl
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractorImpl
import com.android.systemui.biometrics.domain.interactor.FakeCredentialInteractor
import com.android.systemui.biometrics.domain.interactor.PromptCredentialInteractor
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractorImpl
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel
import com.android.systemui.display.data.repository.FakeDisplayRepository
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.events.ANIMATING_OUT
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.Mockito.`when` as whenever


private const val OP_PACKAGE_NAME = "biometric.testapp"

@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
open class AuthContainerViewTest : SysuiTestCase() {

    @JvmField @Rule
    var mockitoRule = MockitoJUnit.rule()

    @Mock
    lateinit var callback: AuthDialogCallback
    @Mock
    lateinit var userManager: UserManager
    @Mock
    lateinit var lockPatternUtils: LockPatternUtils
    @Mock
    lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock
    lateinit var panelInteractionDetector: AuthDialogPanelInteractionDetector
    @Mock
    lateinit var windowToken: IBinder
    @Mock
    lateinit var interactionJankMonitor: InteractionJankMonitor
    @Mock
    lateinit var vibrator: VibratorHelper
    @Mock
    lateinit var udfpsUtils: UdfpsUtils
    @Mock
    lateinit var authController: AuthController
    @Mock
    lateinit var selectedUserInteractor: SelectedUserInteractor
    @Mock
    private lateinit var packageManager: PackageManager
    @Mock private lateinit var activityTaskManager: ActivityTaskManager

    private lateinit var displayRepository: FakeDisplayRepository
    private lateinit var displayStateInteractor: DisplayStateInteractor
    private lateinit var udfpsOverlayInteractor: UdfpsOverlayInteractor
    private lateinit var biometricStatusInteractor: BiometricStatusInteractor
    private lateinit var iconProvider: IconProvider

    private val testScope = TestScope(StandardTestDispatcher())
    private val fakeExecutor = FakeExecutor(FakeSystemClock())
    private val biometricPromptRepository = FakePromptRepository()
    private val biometricStatusRepository = FakeBiometricStatusRepository()
    private val fingerprintRepository = FakeFingerprintPropertyRepository()
    private val displayStateRepository = FakeDisplayStateRepository()
    private val credentialInteractor = FakeCredentialInteractor()
    private val bpCredentialInteractor = PromptCredentialInteractor(
        Dispatchers.Main.immediate,
        biometricPromptRepository,
        credentialInteractor,
    )
    private val promptSelectorInteractor by lazy {
        PromptSelectorInteractorImpl(
            fingerprintRepository,
            displayStateInteractor,
            biometricPromptRepository,
            lockPatternUtils,
        )
    }

    private val credentialViewModel = CredentialViewModel(mContext, bpCredentialInteractor)
    private val defaultLogoIcon = context.getDrawable(R.drawable.ic_android)

    private var authContainer: TestAuthContainerView? = null

    @Before
    fun setup() {
        displayRepository = FakeDisplayRepository()

        displayStateInteractor =
            DisplayStateInteractorImpl(
                    testScope.backgroundScope,
                    mContext,
                    fakeExecutor,
                    displayStateRepository,
                    displayRepository,
            )
        udfpsOverlayInteractor =
                UdfpsOverlayInteractor(
                        context,
                        authController,
                        selectedUserInteractor,
                        testScope.backgroundScope,
                )
        biometricStatusInteractor =
                BiometricStatusInteractorImpl(activityTaskManager, biometricStatusRepository,
                    fingerprintRepository)
        iconProvider = IconProvider(context)
        // Set up default logo icon
        whenever(packageManager.getApplicationIcon(OP_PACKAGE_NAME)).thenReturn(defaultLogoIcon)
        context.setMockPackageManager(packageManager)
    }

    @After
    fun tearDown() {
        if (authContainer?.isAttachedToWindow == true) {
            ViewUtils.detachView(authContainer)
        }
    }

    @Test
    fun testNotifiesAnimatedIn() {
        initializeFingerprintContainer()
        verify(callback).onDialogAnimatedIn(
            authContainer?.requestId ?: 0L,
            true /* startFingerprintNow */
        )
    }

    @Test
    fun testDismissesOnBack() {
        val container = initializeFingerprintContainer(addToView = true)
        assertThat(container.parent).isNotNull()
        val root = container.rootView

        // Simulate back invocation
        container.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        container.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        waitForIdleSync()

        assertThat(container.parent).isNull()
        assertThat(root.isAttachedToWindow).isFalse()
    }

    @Test
    fun testCredentialPasswordDismissesOnBack() {
        val container = initializeCredentialPasswordContainer(addToView = true)
        assertThat(container.parent).isNotNull()
        val root = container.rootView

        // Simulate back invocation
        container.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        container.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        waitForIdleSync()

        assertThat(container.parent).isNull()
        assertThat(root.isAttachedToWindow).isFalse()
    }

    @Test
    fun testIgnoresAnimatedInWhenDismissed() {
        val container = initializeFingerprintContainer(addToView = false)
        container.dismissFromSystemServer()
        waitForIdleSync()

        verify(callback, never()).onDialogAnimatedIn(anyLong(), anyBoolean())

        container.addToView()
        waitForIdleSync()

        // attaching the view resets the state and allows this to happen again
        verify(callback).onDialogAnimatedIn(
            authContainer?.requestId ?: 0L,
            true /* startFingerprintNow */
        )
    }

    @Test
    fun testIgnoresAnimatedInWhenDialogAnimatingOut() {
        val container = initializeFingerprintContainer(addToView = false)
        container.mContainerState = ANIMATING_OUT
        container.addToView()
        waitForIdleSync()

        verify(callback, never()).onDialogAnimatedIn(anyLong(), anyBoolean())
    }

    @Test
    fun testDismissBeforeIntroEnd() {
        val container = initializeFingerprintContainer()
        waitForIdleSync()

        // STATE_ANIMATING_IN = 1
        container?.mContainerState = 1

        container.dismissWithoutCallback(false)

        // the first time is triggered by initializeFingerprintContainer()
        // the second time was triggered by dismissWithoutCallback()
        verify(callback, times(2)).onDialogAnimatedIn(
            authContainer?.requestId ?: 0L,
            true /* startFingerprintNow */
        )
    }

    @Test
    fun testActionCancel_panelInteractionDetectorDisable() {
        val container = initializeFingerprintContainer()
        container.mBiometricCallback.onUserCanceled()
        waitForIdleSync()
        verify(panelInteractionDetector).disable()
    }


    @Test
    fun testActionAuthenticated_sendsDismissedAuthenticated() {
        val container = initializeFingerprintContainer()
        container.mBiometricCallback.onAuthenticated()
        waitForIdleSync()

        verify(callback).onDismissed(
                eq(AuthDialogCallback.DISMISSED_BIOMETRIC_AUTHENTICATED),
                eq<ByteArray?>(null), /* credentialAttestation */
                eq(authContainer?.requestId ?: 0L)
        )
        assertThat(container.parent).isNull()
    }

    @Test
    fun testActionUserCanceled_sendsDismissedUserCanceled() {
        val container = initializeFingerprintContainer()
        container.mBiometricCallback.onUserCanceled()
        waitForIdleSync()

        verify(callback).onSystemEvent(
                eq(BiometricConstants.BIOMETRIC_SYSTEM_EVENT_EARLY_USER_CANCEL),
                eq(authContainer?.requestId ?: 0L)
        )
        verify(callback).onDismissed(
                eq(AuthDialogCallback.DISMISSED_USER_CANCELED),
                eq<ByteArray?>(null), /* credentialAttestation */
                eq(authContainer?.requestId ?: 0L)
        )
        assertThat(container.parent).isNull()
    }

    @Test
    fun testActionButtonNegative_sendsDismissedButtonNegative() {
        val container = initializeFingerprintContainer()
        container.mBiometricCallback.onButtonNegative()
        waitForIdleSync()

        verify(callback).onDismissed(
                eq(AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE),
                eq<ByteArray?>(null), /* credentialAttestation */
                eq(authContainer?.requestId ?: 0L)
        )
        assertThat(container.parent).isNull()
    }

    @Test
    fun testActionTryAgain_sendsTryAgain() {
        val container = initializeFingerprintContainer(
            authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        container.mBiometricCallback.onButtonTryAgain()
        waitForIdleSync()

        verify(callback).onTryAgainPressed(authContainer?.requestId ?: 0L)
    }

    @Test
    fun testActionError_sendsDismissedError() {
        val container = initializeFingerprintContainer()
        container.mBiometricCallback.onError()
        waitForIdleSync()

        verify(callback).onDismissed(
                eq(AuthDialogCallback.DISMISSED_ERROR),
                eq<ByteArray?>(null), /* credentialAttestation */
                eq(authContainer?.requestId ?: 0L)
        )
        assertThat(authContainer!!.parent).isNull()
    }

    @Ignore("b/279650412")
    @Test
    fun testActionUseDeviceCredential_sendsOnDeviceCredentialPressed() {
        val container = initializeFingerprintContainer(
            authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        container.mBiometricCallback.onUseDeviceCredential()
        waitForIdleSync()

        verify(callback).onDeviceCredentialPressed(authContainer?.requestId ?: 0L)
        assertThat(container.hasCredentialView()).isTrue()
    }

    @Test
    fun testAnimateToCredentialUI_invokesStartTransitionToCredentialUI() {
        val container = initializeFingerprintContainer(
            authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        container.animateToCredentialUI(false)
        waitForIdleSync()

        assertThat(container.hasCredentialView()).isTrue()
        assertThat(container.hasBiometricPrompt()).isFalse()

        // Check credential view persists after new attachment
        container.onAttachedToWindow()

        assertThat(container.hasCredentialView()).isTrue()
        assertThat(container.hasBiometricPrompt()).isFalse()
    }

    @Test
    fun testAnimateToCredentialUI_rotateCredentialUI() {
        val container = initializeFingerprintContainer(
            authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        container.animateToCredentialUI(false)
        waitForIdleSync()

        assertThat(container.hasCredentialView()).isTrue()
        assertThat(container.hasBiometricPrompt()).isFalse()

        // Check credential view persists after new attachment
        container.onAttachedToWindow()

        assertThat(container.hasCredentialView()).isTrue()
        assertThat(container.hasBiometricPrompt()).isFalse()

        val configuration = Configuration(context.resources.configuration)
        configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
        container.dispatchConfigurationChanged(configuration)
        waitForIdleSync()

        assertThat(container.hasCredentialView()).isTrue()
        assertThat(container.hasBiometricPrompt()).isFalse()
    }

    @Test
    fun testShowBiometricUI() {
        mSetFlagsRule.disableFlags(FLAG_CONSTRAINT_BP)
        val container = initializeFingerprintContainer()

        waitForIdleSync()

        assertThat(container.hasCredentialView()).isFalse()
        assertThat(container.hasBiometricPrompt()).isTrue()
    }

    @Test
    fun testShowBiometricUI_ContentViewWithMoreOptionsButton() {
        mSetFlagsRule.enableFlags(FLAG_CONSTRAINT_BP)
        mSetFlagsRule.enableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
        var isButtonClicked = false
        val contentView =
                PromptContentViewWithMoreOptionsButton.Builder()
                        .setMoreOptionsButtonListener(
                                fakeExecutor) { _, _ -> isButtonClicked = true }
                        .build()

        val container =
                initializeFingerprintContainer(contentViewWithMoreOptionsButton = contentView)

        waitForIdleSync()

        assertThat(container.hasCredentialView()).isFalse()
        assertThat(container.hasConstraintBiometricPrompt()).isTrue()

        // TODO(b/328843028): Use button.performClick() instead of calling
        //  onContentViewMoreOptionsButtonPressed() directly, and check |isButtonClicked| is true.
        container.mBiometricCallback.onContentViewMoreOptionsButtonPressed()
        waitForIdleSync()
        // container is gone
        assertThat(container.mContainerState).isEqualTo(5)
    }

    @Test
    fun testShowCredentialUI_withDescription() {
        val container =
                initializeFingerprintContainer(
                        authenticators = BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
        waitForIdleSync()

        assertThat(container.hasCredentialView()).isTrue()
        assertThat(container.hasBiometricPrompt()).isFalse()
    }

    @Test
    fun testShowCredentialUI_withVerticalListContentView() {
        mSetFlagsRule.enableFlags(FLAG_CONSTRAINT_BP)
        mSetFlagsRule.enableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
        val container =
                initializeFingerprintContainer(
                        authenticators = BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                        verticalListContentView = PromptVerticalListContentView.Builder().build()
                )
        // Two-step credential view should show -
        // 1. biometric prompt without sensor 2. credential view ui
        waitForIdleSync()
        assertThat(container.hasConstraintBiometricPrompt()).isTrue()
        assertThat(container.hasCredentialView()).isFalse()

        container.animateToCredentialUI(false)
        waitForIdleSync()
        // TODO(b/302735104): Check the reason why hasConstraintBiometricPrompt() is still true
        // assertThat(container.hasConstraintBiometricPrompt()).isFalse()
        assertThat(container.hasCredentialView()).isTrue()
    }

    @Test
    fun testShowCredentialUI_withContentViewWithMoreOptionsButton() {
        mSetFlagsRule.enableFlags(FLAG_CONSTRAINT_BP)
        mSetFlagsRule.enableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
        val contentView =
                PromptContentViewWithMoreOptionsButton.Builder()
                        .setMoreOptionsButtonListener(fakeExecutor) { _, _ -> }
                        .build()
        val container =
                initializeFingerprintContainer(
                        authenticators = BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                        contentViewWithMoreOptionsButton = contentView
                )
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

        val container = initializeFingerprintContainer(
            authenticators = BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        waitForIdleSync()

        assertThat(container.hasCredentialPatternView()).isTrue()
        assertThat(container.hasBiometricPrompt()).isFalse()
    }

    @Test
    fun testCredentialUI_disablesClickingOnBackground() {
        val container = initializeCredentialPasswordContainer()
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
    fun testLayoutParams_hasShowWhenLockedFlag() {
        val layoutParams = AuthContainerView.getLayoutParams(windowToken, "")
        assertThat((layoutParams.flags and WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) != 0)
                .isTrue()
    }

    @Test
    fun testLayoutParams_hasDimbehindWindowFlag() {
        val layoutParams = AuthContainerView.getLayoutParams(windowToken, "")
        val lpFlags = layoutParams.flags
        val lpDimAmount = layoutParams.dimAmount

        assertThat((lpFlags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0).isTrue()
        assertThat(lpDimAmount).isGreaterThan(0f)
    }

    @Test
    fun testLayoutParams_excludesImeInsets() {
        val layoutParams = AuthContainerView.getLayoutParams(windowToken, "")
        assertThat((layoutParams.fitInsetsTypes and WindowInsets.Type.ime()) == 0).isTrue()
    }

    @Test
    fun coexFaceRestartsOnTouch() {
        val container = initializeCoexContainer()

        container.onPointerDown()
        waitForIdleSync()

        container.onAuthenticationFailed(BiometricAuthenticator.TYPE_FACE, "failed")
        waitForIdleSync()

        verify(callback, never()).onTryAgainPressed(anyLong())

        container.onPointerDown()
        waitForIdleSync()

        verify(callback).onTryAgainPressed(authContainer?.requestId ?: 0L)
    }

    private fun initializeCredentialPasswordContainer(
            addToView: Boolean = true,
    ): TestAuthContainerView {
        whenever(userManager.getCredentialOwnerProfile(anyInt())).thenReturn(20)
        whenever(lockPatternUtils.getKeyguardStoredPasswordQuality(eq(20))).thenReturn(
            DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
        )

        // In the credential view, clicking on the background (to cancel authentication) is not
        // valid. Thus, the listener should be null, and it should not be in the accessibility
        // hierarchy.
        val container = initializeFingerprintContainer(
                authenticators = BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                addToView = addToView,
        )
        waitForIdleSync()

        assertThat(container.hasCredentialPasswordView()).isTrue()
        return container
    }

    private fun initializeFingerprintContainer(
        authenticators: Int = BiometricManager.Authenticators.BIOMETRIC_WEAK,
        addToView: Boolean = true,
        verticalListContentView: PromptVerticalListContentView? = null,
        contentViewWithMoreOptionsButton: PromptContentViewWithMoreOptionsButton? = null,
    ) = initializeContainer(
        TestAuthContainerView(
            authenticators = authenticators,
            fingerprintProps = fingerprintSensorPropertiesInternal(),
                verticalListContentView = verticalListContentView,
        ),
        addToView
    )

    private fun initializeCoexContainer(
        authenticators: Int = BiometricManager.Authenticators.BIOMETRIC_WEAK,
        addToView: Boolean = true
    ) = initializeContainer(
        TestAuthContainerView(
            authenticators = authenticators,
            fingerprintProps = fingerprintSensorPropertiesInternal(),
            faceProps = faceSensorPropertiesInternal()
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
        }

        return authContainer!!
    }

    private inner class TestAuthContainerView(
        authenticators: Int = BiometricManager.Authenticators.BIOMETRIC_WEAK,
        fingerprintProps: List<FingerprintSensorPropertiesInternal> = listOf(),
        faceProps: List<FaceSensorPropertiesInternal> = listOf(),
        verticalListContentView: PromptVerticalListContentView? = null,
        contentViewWithMoreOptionsButton: PromptContentViewWithMoreOptionsButton? = null,
    ) : AuthContainerView(
        Config().apply {
            mContext = this@AuthContainerViewTest.context
            mCallback = callback
            mSensorIds = (fingerprintProps.map { it.sensorId } +
                faceProps.map { it.sensorId }).toIntArray()
            mSkipAnimation = true
            mPromptInfo = PromptInfo().apply {
                this.authenticators = authenticators
                if (verticalListContentView != null) {
                    this.contentView = verticalListContentView
                } else if (contentViewWithMoreOptionsButton != null) {
                    this.contentView = contentViewWithMoreOptionsButton
                }
            }
            mOpPackageName = OP_PACKAGE_NAME
        },
        testScope.backgroundScope,
        fingerprintProps,
        faceProps,
        wakefulnessLifecycle,
        panelInteractionDetector,
        userManager,
        lockPatternUtils,
        interactionJankMonitor,
        { promptSelectorInteractor },
        PromptViewModel(
            displayStateInteractor,
            promptSelectorInteractor,
            context,
            udfpsOverlayInteractor,
            biometricStatusInteractor,
            udfpsUtils,
            iconProvider,
            activityTaskManager
        ),
        { credentialViewModel },
        Handler(TestableLooper.get(this).looper),
        fakeExecutor,
        vibrator
    ) {
        override fun postOnAnimation(runnable: Runnable) {
            runnable.run()
        }
    }

    override fun waitForIdleSync() {
        testScope.runCurrent()
        TestableLooper.get(this).processAllMessages()
    }

    private fun AuthContainerView.addToView() {
        ViewUtils.attachView(this)
        waitForIdleSync()
        assertThat(isAttachedToWindow()).isTrue()
    }

    @Test
    fun testLayoutParams_hasCutoutModeAlwaysFlag() {
        val layoutParams = AuthContainerView.getLayoutParams(windowToken, "")
        val lpFlags = layoutParams.flags

        assertThat((lpFlags and WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS)
                != 0).isTrue()
    }

    @Test
    fun testLayoutParams_excludesSystemBarInsets() {
        val layoutParams = AuthContainerView.getLayoutParams(windowToken, "")
        assertThat((layoutParams.fitInsetsTypes and WindowInsets.Type.systemBars()) == 0).isTrue()
    }
}

private fun AuthContainerView.hasConstraintBiometricPrompt() =
    (findViewById<ConstraintLayout>(R.id.biometric_prompt_constraint_layout)?.childCount ?: 0) > 0

private fun AuthContainerView.hasBiometricPrompt() =
    (findViewById<ScrollView>(R.id.biometric_scrollview)?.childCount ?: 0) > 0

private fun AuthContainerView.hasCredentialView() =
    hasCredentialPatternView() || hasCredentialPasswordView()

private fun AuthContainerView.hasCredentialPatternView() =
    findViewById<View>(R.id.lockPattern) != null

private fun AuthContainerView.hasCredentialPasswordView() =
    findViewById<View>(R.id.lockPassword) != null

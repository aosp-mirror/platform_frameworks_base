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

package com.android.keyguard

import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.internal.util.LatencyTracker
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockscreenCredential
import com.android.keyguard.KeyguardPinViewController.PinBouncerUiEvent
import com.android.keyguard.KeyguardSecurityModel.SecurityMode
import com.android.keyguard.domain.interactor.KeyguardKeyboardInteractor
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.haptics.msdl.bouncerHapticPlayer
import com.android.systemui.keyboard.data.repository.FakeKeyboardRepository
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.DevicePostureController
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_HALF_OPENED
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_OPENED
import com.android.systemui.testKosmos
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
// collectFlow in KeyguardPinBasedInputViewController.onViewAttached calls JavaAdapter.CollectFlow,
// which calls View.onRepeatWhenAttached, which requires being run on main thread.
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class KeyguardPinViewControllerTest : SysuiTestCase() {

    private lateinit var objectKeyguardPINView: KeyguardPINView

    @Mock private lateinit var mockKeyguardPinView: KeyguardPINView

    @Mock private lateinit var keyguardMessageArea: BouncerKeyguardMessageArea

    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor

    @Mock private lateinit var securityMode: SecurityMode

    @Mock private lateinit var lockPatternUtils: LockPatternUtils

    @Mock private lateinit var mKeyguardSecurityCallback: KeyguardSecurityCallback

    @Mock
    private lateinit var keyguardMessageAreaControllerFactory: KeyguardMessageAreaController.Factory

    @Mock
    private lateinit var keyguardMessageAreaController:
        KeyguardMessageAreaController<BouncerKeyguardMessageArea>

    @Mock private lateinit var mLatencyTracker: LatencyTracker

    @Mock private lateinit var liftToActivateListener: LiftToActivateListener

    @Mock private val mEmergencyButtonController: EmergencyButtonController? = null
    private val falsingCollector: FalsingCollector = FalsingCollectorFake()
    private val keyguardKeyboardInteractor = KeyguardKeyboardInteractor(FakeKeyboardRepository())
    private val passwordTextViewLayoutParams =
        ViewGroup.LayoutParams(/* width= */ 0, /* height= */ 0)
    @Mock lateinit var postureController: DevicePostureController
    @Mock lateinit var mSelectedUserInteractor: SelectedUserInteractor

    @Mock lateinit var featureFlags: FeatureFlags
    @Mock lateinit var passwordTextView: PasswordTextView
    @Mock lateinit var deleteButton: NumPadButton
    @Mock lateinit var enterButton: View
    @Mock lateinit var uiEventLogger: UiEventLogger
    @Mock lateinit var mUserActivityNotifier: UserActivityNotifier

    @Captor lateinit var postureCallbackCaptor: ArgumentCaptor<DevicePostureController.Callback>

    private val kosmos = testKosmos()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        `when`(mockKeyguardPinView.requireViewById<View>(R.id.bouncer_message_area))
            .thenReturn(keyguardMessageArea)
        `when`(keyguardMessageAreaControllerFactory.create(any(KeyguardMessageArea::class.java)))
            .thenReturn(keyguardMessageAreaController)
        `when`(mockKeyguardPinView.passwordTextViewId).thenReturn(R.id.pinEntry)
        `when`(mockKeyguardPinView.findViewById<PasswordTextView>(R.id.pinEntry))
            .thenReturn(passwordTextView)
        `when`(mockKeyguardPinView.resources).thenReturn(context.resources)
        `when`(mockKeyguardPinView.findViewById<NumPadButton>(R.id.delete_button))
            .thenReturn(deleteButton)
        `when`(mockKeyguardPinView.findViewById<View>(R.id.key_enter)).thenReturn(enterButton)
        // For posture tests:
        `when`(mockKeyguardPinView.buttons).thenReturn(arrayOf())
        `when`(lockPatternUtils.getPinLength(anyInt())).thenReturn(6)
        `when`(featureFlags.isEnabled(Flags.LOCKSCREEN_ENABLE_LANDSCAPE)).thenReturn(false)
        `when`(passwordTextView.layoutParams).thenReturn(passwordTextViewLayoutParams)

        objectKeyguardPINView =
            View.inflate(mContext, R.layout.keyguard_pin_view, null)
                .requireViewById(R.id.keyguard_pin_view) as KeyguardPINView
    }

    private fun constructPinViewController(
        mKeyguardPinView: KeyguardPINView
    ): KeyguardPinViewController {
        return KeyguardPinViewController(
            mKeyguardPinView,
            keyguardUpdateMonitor,
            securityMode,
            lockPatternUtils,
            mKeyguardSecurityCallback,
            keyguardMessageAreaControllerFactory,
            mLatencyTracker,
            liftToActivateListener,
            mEmergencyButtonController,
            falsingCollector,
            postureController,
            featureFlags,
            mSelectedUserInteractor,
            uiEventLogger,
            keyguardKeyboardInteractor,
            kosmos.bouncerHapticPlayer,
            mUserActivityNotifier,
        )
    }

    @Test
    fun onViewAttached_deviceHalfFolded_propagatedToPatternView() {
        val pinViewController = constructPinViewController(objectKeyguardPINView)
        overrideResource(R.dimen.half_opened_bouncer_height_ratio, 0.5f)
        whenever(postureController.devicePosture).thenReturn(DEVICE_POSTURE_HALF_OPENED)

        pinViewController.onViewAttached()

        assertThat(getPinTopGuideline()).isEqualTo(getHalfOpenedBouncerHeightRatio())
    }

    @Test
    fun onDevicePostureChanged_deviceOpened_propagatedToPatternView() {
        val pinViewController = constructPinViewController(objectKeyguardPINView)
        overrideResource(R.dimen.half_opened_bouncer_height_ratio, 0.5f)

        whenever(postureController.devicePosture).thenReturn(DEVICE_POSTURE_HALF_OPENED)
        pinViewController.onViewAttached()

        // Verify view begins in posture state DEVICE_POSTURE_HALF_OPENED
        assertThat(getPinTopGuideline()).isEqualTo(getHalfOpenedBouncerHeightRatio())

        // Simulate posture change to state DEVICE_POSTURE_OPENED with callback
        verify(postureController).addCallback(postureCallbackCaptor.capture())
        val postureCallback: DevicePostureController.Callback = postureCallbackCaptor.value
        postureCallback.onPostureChanged(DEVICE_POSTURE_OPENED)

        // Verify view is now in posture state DEVICE_POSTURE_OPENED
        assertThat(getPinTopGuideline()).isNotEqualTo(getHalfOpenedBouncerHeightRatio())

        // Simulate posture change to same state with callback
        postureCallback.onPostureChanged(DEVICE_POSTURE_OPENED)

        // Verify view is still in posture state DEVICE_POSTURE_OPENED
        assertThat(getPinTopGuideline()).isNotEqualTo(getHalfOpenedBouncerHeightRatio())
    }

    private fun getPinTopGuideline(): Float {
        val cs = ConstraintSet()
        val container =
            objectKeyguardPINView.requireViewById(R.id.pin_container) as ConstraintLayout
        cs.clone(container)
        return cs.getConstraint(R.id.pin_pad_top_guideline).layout.guidePercent
    }

    private fun getHalfOpenedBouncerHeightRatio(): Float {
        return mContext.resources.getFloat(R.dimen.half_opened_bouncer_height_ratio)
    }

    @Test
    fun testOnViewAttached() {
        val pinViewController = constructPinViewController(mockKeyguardPinView)

        pinViewController.onViewAttached()

        verify(keyguardMessageAreaController)
            .setMessage(context.resources.getString(R.string.keyguard_enter_your_pin), false)
    }

    @Test
    fun testOnViewAttached_withExistingMessage() {
        val pinViewController = constructPinViewController(mockKeyguardPinView)
        Mockito.`when`(keyguardMessageAreaController.message).thenReturn("Unlock to continue.")

        pinViewController.onViewAttached()

        verify(keyguardMessageAreaController, Mockito.never()).setMessage(anyString(), anyBoolean())
    }

    @Test
    fun testOnViewAttached_withAutoPinConfirmationFailedPasswordAttemptsLessThan5() {
        val pinViewController = constructPinViewController(mockKeyguardPinView)
        `when`(featureFlags.isEnabled(Flags.AUTO_PIN_CONFIRMATION)).thenReturn(true)
        `when`(lockPatternUtils.getPinLength(anyInt())).thenReturn(6)
        `when`(lockPatternUtils.isAutoPinConfirmEnabled(anyInt())).thenReturn(true)
        `when`(lockPatternUtils.getCurrentFailedPasswordAttempts(anyInt())).thenReturn(3)
        `when`(passwordTextView.text).thenReturn("")

        pinViewController.onViewAttached()

        verify(deleteButton).visibility = View.INVISIBLE
        verify(enterButton).visibility = View.INVISIBLE
        verify(passwordTextView).setUsePinShapes(true)
        verify(passwordTextView).setIsPinHinting(true)
    }

    @Test
    fun testOnViewAttached_withAutoPinConfirmationFailedPasswordAttemptsMoreThan5() {
        val pinViewController = constructPinViewController(mockKeyguardPinView)
        `when`(featureFlags.isEnabled(Flags.AUTO_PIN_CONFIRMATION)).thenReturn(true)
        `when`(lockPatternUtils.getPinLength(anyInt())).thenReturn(6)
        `when`(lockPatternUtils.isAutoPinConfirmEnabled(anyInt())).thenReturn(true)
        `when`(lockPatternUtils.getCurrentFailedPasswordAttempts(anyInt())).thenReturn(6)
        `when`(passwordTextView.text).thenReturn("")

        pinViewController.onViewAttached()

        verify(deleteButton).visibility = View.VISIBLE
        verify(enterButton).visibility = View.VISIBLE
        verify(passwordTextView).setUsePinShapes(true)
        verify(passwordTextView).setIsPinHinting(false)
    }

    @Test
    fun handleLockout_readsNumberOfErrorAttempts() {
        val pinViewController = constructPinViewController(mockKeyguardPinView)

        pinViewController.handleAttemptLockout(0)

        verify(lockPatternUtils).getCurrentFailedPasswordAttempts(anyInt())
    }

    @Test
    fun onUserInput_autoConfirmation_attemptsUnlock() {
        val pinViewController = constructPinViewController(mockKeyguardPinView)
        whenever(featureFlags.isEnabled(Flags.AUTO_PIN_CONFIRMATION)).thenReturn(true)
        whenever(lockPatternUtils.getPinLength(anyInt())).thenReturn(6)
        whenever(lockPatternUtils.isAutoPinConfirmEnabled(anyInt())).thenReturn(true)
        whenever(passwordTextView.text).thenReturn("000000")
        whenever(enterButton.visibility).thenReturn(View.INVISIBLE)
        whenever(mockKeyguardPinView.enteredCredential)
            .thenReturn(LockscreenCredential.createPin("000000"))

        pinViewController.onUserInput()

        verify(uiEventLogger).log(PinBouncerUiEvent.ATTEMPT_UNLOCK_WITH_AUTO_CONFIRM_FEATURE)
        verify(keyguardUpdateMonitor).setCredentialAttempted()
    }
}

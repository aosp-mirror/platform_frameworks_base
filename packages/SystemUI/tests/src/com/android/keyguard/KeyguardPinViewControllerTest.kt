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

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.util.LatencyTracker
import com.android.internal.widget.LockPatternUtils
import com.android.keyguard.KeyguardSecurityModel.SecurityMode
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.policy.DevicePostureController
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class KeyguardPinViewControllerTest : SysuiTestCase() {
    @Mock private lateinit var keyguardPinView: KeyguardPINView

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
    @Mock lateinit var postureController: DevicePostureController

    @Mock lateinit var featureFlags: FeatureFlags
    @Mock lateinit var passwordTextView: PasswordTextView
    @Mock lateinit var deleteButton: NumPadButton
    @Mock lateinit var enterButton: View

    lateinit var pinViewController: KeyguardPinViewController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        Mockito.`when`(keyguardPinView.requireViewById<View>(R.id.bouncer_message_area))
            .thenReturn(keyguardMessageArea)
        Mockito.`when`(
                keyguardMessageAreaControllerFactory.create(any(KeyguardMessageArea::class.java))
            )
            .thenReturn(keyguardMessageAreaController)
        `when`(keyguardPinView.passwordTextViewId).thenReturn(R.id.pinEntry)
        `when`(keyguardPinView.findViewById<PasswordTextView>(R.id.pinEntry))
            .thenReturn(passwordTextView)
        `when`(keyguardPinView.resources).thenReturn(context.resources)
        `when`(keyguardPinView.findViewById<NumPadButton>(R.id.delete_button))
            .thenReturn(deleteButton)
        `when`(keyguardPinView.findViewById<View>(R.id.key_enter)).thenReturn(enterButton)
        constructViewController()
    }

    @Test
    fun startAppearAnimation() {
        pinViewController.startAppearAnimation()
        verify(keyguardMessageAreaController)
            .setMessage(context.resources.getString(R.string.keyguard_enter_your_pin), false)
    }

    @Test
    fun startAppearAnimation_withExistingMessage() {
        Mockito.`when`(keyguardMessageAreaController.message).thenReturn("Unlock to continue.")
        pinViewController.startAppearAnimation()
        verify(keyguardMessageAreaController, Mockito.never()).setMessage(anyString(), anyBoolean())
    }

    @Test
    fun startAppearAnimation_withAutoPinConfirmationFailedPasswordAttemptsLessThan5() {
        `when`(featureFlags.isEnabled(Flags.AUTO_PIN_CONFIRMATION)).thenReturn(true)
        `when`(lockPatternUtils.getPinLength(anyInt())).thenReturn(6)
        `when`(lockPatternUtils.isAutoPinConfirmEnabled(anyInt())).thenReturn(true)
        `when`(lockPatternUtils.getCurrentFailedPasswordAttempts(anyInt())).thenReturn(3)
        `when`(passwordTextView.text).thenReturn("")
        constructViewController()

        pinViewController.startAppearAnimation()

        verify(deleteButton).visibility = View.INVISIBLE
        verify(enterButton).visibility = View.INVISIBLE
        verify(passwordTextView).setUsePinShapes(true)
        verify(passwordTextView).setIsPinHinting(true)
    }

    @Test
    fun startAppearAnimation_withAutoPinConfirmationFailedPasswordAttemptsMoreThan5() {
        `when`(featureFlags.isEnabled(Flags.AUTO_PIN_CONFIRMATION)).thenReturn(true)
        `when`(lockPatternUtils.getPinLength(anyInt())).thenReturn(6)
        `when`(lockPatternUtils.isAutoPinConfirmEnabled(anyInt())).thenReturn(true)
        `when`(lockPatternUtils.getCurrentFailedPasswordAttempts(anyInt())).thenReturn(6)
        `when`(passwordTextView.text).thenReturn("")
        constructViewController()

        pinViewController.startAppearAnimation()

        verify(deleteButton).visibility = View.VISIBLE
        verify(enterButton).visibility = View.VISIBLE
        verify(passwordTextView).setUsePinShapes(true)
        verify(passwordTextView).setIsPinHinting(false)
    }

    @Test
    fun handleLockout_readsNumberOfErrorAttempts() {
        pinViewController.handleAttemptLockout(0)
        verify(lockPatternUtils).getCurrentFailedPasswordAttempts(anyInt())
    }

    fun constructViewController() {
        pinViewController =
            KeyguardPinViewController(
                keyguardPinView,
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
                featureFlags
            )
    }
}

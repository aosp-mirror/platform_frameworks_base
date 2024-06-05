/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.telephony.PinResult
import android.telephony.TelephonyManager
import android.testing.TestableLooper
import android.view.LayoutInflater
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.util.LatencyTracker
import com.android.internal.widget.LockPatternUtils
import com.android.keyguard.domain.interactor.KeyguardKeyboardInteractor
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.keyboard.data.repository.FakeKeyboardRepository
import com.android.systemui.res.R
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.mockito.any
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
// collectFlow in KeyguardPinBasedInputViewController.onViewAttached calls JavaAdapter.CollectFlow,
// which calls View.onRepeatWhenAttached, which requires being run on main thread.
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class KeyguardSimPukViewControllerTest : SysuiTestCase() {
    private lateinit var simPukView: KeyguardSimPukView
    private lateinit var underTest: KeyguardSimPukViewController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var securityMode: KeyguardSecurityModel.SecurityMode
    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var keyguardSecurityCallback: KeyguardSecurityCallback
    @Mock private lateinit var messageAreaControllerFactory: KeyguardMessageAreaController.Factory
    @Mock private lateinit var latencyTracker: LatencyTracker
    @Mock private lateinit var liftToActivateListener: LiftToActivateListener
    @Mock private lateinit var telephonyManager: TelephonyManager
    @Mock private lateinit var falsingCollector: FalsingCollector
    @Mock private lateinit var emergencyButtonController: EmergencyButtonController
    @Mock private lateinit var mSelectedUserInteractor: SelectedUserInteractor
    @Mock
    private lateinit var keyguardMessageAreaController:
        KeyguardMessageAreaController<BouncerKeyguardMessageArea>

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        Mockito.`when`(
                messageAreaControllerFactory.create(Mockito.any(KeyguardMessageArea::class.java))
            )
            .thenReturn(keyguardMessageAreaController)
        Mockito.`when`(telephonyManager.createForSubscriptionId(Mockito.anyInt()))
            .thenReturn(telephonyManager)
        Mockito.`when`(telephonyManager.supplyIccLockPuk(anyString(), anyString()))
            .thenReturn(Mockito.mock(PinResult::class.java))
        simPukView =
            LayoutInflater.from(context).inflate(R.layout.keyguard_sim_puk_view, null)
                as KeyguardSimPukView
        val keyguardKeyboardInteractor = KeyguardKeyboardInteractor(FakeKeyboardRepository())
        val fakeFeatureFlags = FakeFeatureFlags()
        mSetFlagsRule.enableFlags(Flags.FLAG_REVAMPED_BOUNCER_MESSAGES)
        underTest =
            KeyguardSimPukViewController(
                simPukView,
                keyguardUpdateMonitor,
                securityMode,
                lockPatternUtils,
                keyguardSecurityCallback,
                messageAreaControllerFactory,
                latencyTracker,
                liftToActivateListener,
                telephonyManager,
                falsingCollector,
                emergencyButtonController,
                fakeFeatureFlags,
                mSelectedUserInteractor,
                keyguardKeyboardInteractor
            )
        underTest.init()
    }

    @Test
    fun onViewAttached() {
        Mockito.reset(keyguardMessageAreaController)
        underTest.onViewAttached()
        Mockito.verify(keyguardMessageAreaController).setIsVisible(true)
        Mockito.verify(keyguardUpdateMonitor)
            .registerCallback(any(KeyguardUpdateMonitorCallback::class.java))
        Mockito.verify(keyguardMessageAreaController)
            .setMessage(context.resources.getString(R.string.keyguard_enter_your_pin), false)
    }

    @Test
    fun onViewDetached() {
        underTest.onViewDetached()
        Mockito.verify(keyguardUpdateMonitor)
            .removeCallback(any(KeyguardUpdateMonitorCallback::class.java))
    }

    @Test
    fun onResume() {
        underTest.onResume(KeyguardSecurityView.VIEW_REVEALED)
    }

    @Test
    fun onPause() {
        underTest.onPause()
    }

    @Test
    fun startAppearAnimation() {
        underTest.startAppearAnimation()
    }

    @Test
    fun startDisappearAnimation() {
        underTest.startDisappearAnimation {}
    }

    @Test
    fun resetState() {
        underTest.resetState()
        Mockito.verify(keyguardMessageAreaController)
            .setMessage(context.resources.getString(R.string.kg_puk_enter_puk_hint))
    }
}

/*
 * Copyright (C) 2020 The Android Open Source Project
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
import androidx.test.filters.SmallTest
import com.android.internal.util.LatencyTracker
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternView
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.statusbar.policy.DevicePostureController
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class KeyguardPatternViewControllerTest : SysuiTestCase() {
    @Mock
    private lateinit var mKeyguardPatternView: KeyguardPatternView
    @Mock
    private lateinit var mKeyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock
    private lateinit var mSecurityMode: KeyguardSecurityModel.SecurityMode
    @Mock
    private lateinit var mLockPatternUtils: LockPatternUtils
    @Mock
    private lateinit var mKeyguardSecurityCallback: KeyguardSecurityCallback
    @Mock
    private lateinit var mLatencyTracker: LatencyTracker
    private var mFalsingCollector: FalsingCollector = FalsingCollectorFake()
    @Mock
    private lateinit var mEmergencyButtonController: EmergencyButtonController
    @Mock
    private lateinit
    var mKeyguardMessageAreaControllerFactory: KeyguardMessageAreaController.Factory
    @Mock
    private lateinit var mKeyguardMessageArea: KeyguardMessageArea
    @Mock
    private lateinit var mKeyguardMessageAreaController: KeyguardMessageAreaController
    @Mock
    private lateinit var mLockPatternView: LockPatternView
    @Mock
    private lateinit var mPostureController: DevicePostureController

    private lateinit var mKeyguardPatternViewController: KeyguardPatternViewController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        `when`(mKeyguardPatternView.isAttachedToWindow).thenReturn(true)
        `when`(mKeyguardPatternView.findViewById<KeyguardMessageArea>(R.id.keyguard_message_area))
                .thenReturn(mKeyguardMessageArea)
        `when`(mKeyguardPatternView.findViewById<LockPatternView>(R.id.lockPatternView))
                .thenReturn(mLockPatternView)
        `when`(mKeyguardMessageAreaControllerFactory.create(mKeyguardMessageArea))
                .thenReturn(mKeyguardMessageAreaController)
        mKeyguardPatternViewController = KeyguardPatternViewController(mKeyguardPatternView,
        mKeyguardUpdateMonitor, mSecurityMode, mLockPatternUtils, mKeyguardSecurityCallback,
                mLatencyTracker, mFalsingCollector, mEmergencyButtonController,
                mKeyguardMessageAreaControllerFactory, mPostureController)
    }

    @Test
    fun onPause_clearsTextField() {
        mKeyguardPatternViewController.init()
        mKeyguardPatternViewController.onPause()
        verify(mKeyguardMessageAreaController).setMessage("")
    }
}

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
import android.view.inputmethod.InputMethodManager
import androidx.test.filters.SmallTest
import com.android.internal.util.LatencyTracker
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.util.concurrency.DelayableExecutor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class KeyguardPasswordViewControllerTest : SysuiTestCase() {
    @Mock
    private lateinit var keyguardPasswordView: KeyguardPasswordView
    @Mock
    lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock
    lateinit var securityMode: KeyguardSecurityModel.SecurityMode
    @Mock
    lateinit var lockPatternUtils: LockPatternUtils
    @Mock
    lateinit var keyguardSecurityCallback: KeyguardSecurityCallback
    @Mock
    lateinit var messageAreaControllerFactory: KeyguardMessageAreaController.Factory
    @Mock
    lateinit var latencyTracker: LatencyTracker
    @Mock
    lateinit var inputMethodManager: InputMethodManager
    @Mock
    lateinit var emergencyButtonController: EmergencyButtonController
    @Mock
    lateinit var mainExecutor: DelayableExecutor
    @Mock
    lateinit var falsingCollector: FalsingCollector
    @Mock
    lateinit var keyguardViewController: KeyguardViewController
    @Mock
    private lateinit var mKeyguardMessageArea: KeyguardMessageArea
    @Mock
    private lateinit var mKeyguardMessageAreaController: KeyguardMessageAreaController

    private lateinit var keyguardPasswordViewController: KeyguardPasswordViewController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        Mockito.`when`(keyguardPasswordView
                .findViewById<KeyguardMessageArea>(R.id.keyguard_message_area))
                .thenReturn(mKeyguardMessageArea)
        Mockito.`when`(messageAreaControllerFactory.create(mKeyguardMessageArea))
                .thenReturn(mKeyguardMessageAreaController)
        keyguardPasswordViewController = KeyguardPasswordViewController(
                keyguardPasswordView,
                keyguardUpdateMonitor,
                securityMode,
                lockPatternUtils,
                keyguardSecurityCallback,
                messageAreaControllerFactory,
                latencyTracker,
                inputMethodManager,
                emergencyButtonController,
                mainExecutor,
                mContext.resources,
                falsingCollector,
                keyguardViewController
        )
    }

    @Test
    fun testFocusWhenBouncerIsShown() {
        Mockito.`when`(keyguardViewController.isBouncerShowing).thenReturn(true)
        Mockito.`when`(keyguardPasswordView.isShown).thenReturn(true)
        keyguardPasswordViewController.onResume(KeyguardSecurityView.VIEW_REVEALED)
        keyguardPasswordView.post { verify(keyguardPasswordView).requestFocus() }
    }

    @Test
    fun testDoNotFocusWhenBouncerIsHidden() {
        Mockito.`when`(keyguardViewController.isBouncerShowing).thenReturn(false)
        Mockito.`when`(keyguardPasswordView.isShown).thenReturn(true)
        keyguardPasswordViewController.onResume(KeyguardSecurityView.VIEW_REVEALED)
        verify(keyguardPasswordView, never()).requestFocus()
    }
}
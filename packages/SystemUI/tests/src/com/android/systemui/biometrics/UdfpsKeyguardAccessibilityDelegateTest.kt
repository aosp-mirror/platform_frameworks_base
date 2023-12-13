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

package com.android.systemui.biometrics

import android.testing.TestableLooper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.util.mockito.argumentCaptor
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
@TestableLooper.RunWithLooper
class UdfpsKeyguardAccessibilityDelegateTest : SysuiTestCase() {

    @Mock private lateinit var keyguardViewManager: StatusBarKeyguardViewManager
    @Mock private lateinit var hostView: View
    private lateinit var underTest: UdfpsKeyguardAccessibilityDelegate

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            UdfpsKeyguardAccessibilityDelegate(
                context.resources,
                keyguardViewManager,
            )
    }

    @Test
    fun onInitializeAccessibilityNodeInfo_clickActionAdded() {
        // WHEN node is initialized
        val mockedNodeInfo = mock(AccessibilityNodeInfo::class.java)
        underTest.onInitializeAccessibilityNodeInfo(hostView, mockedNodeInfo)

        // THEN a11y action is added
        val argumentCaptor = argumentCaptor<AccessibilityNodeInfo.AccessibilityAction>()
        verify(mockedNodeInfo).addAction(argumentCaptor.capture())

        // AND the a11y action is a click action
        assertEquals(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id,
            argumentCaptor.value.id
        )
    }

    @Test
    fun performAccessibilityAction_actionClick_showsPrimaryBouncer() {
        // WHEN click action is performed
        val mockedNodeInfo = mock(AccessibilityNodeInfo::class.java)
        underTest.performAccessibilityAction(
            hostView,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id,
            null
        )

        // THEN primary bouncer shows
        verify(keyguardViewManager).showPrimaryBouncer(anyBoolean())
    }
}

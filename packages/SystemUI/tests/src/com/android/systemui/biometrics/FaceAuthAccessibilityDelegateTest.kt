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
import com.android.systemui.keyguard.domain.interactor.KeyguardFaceAuthInteractor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.whenever
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
@TestableLooper.RunWithLooper
class FaceAuthAccessibilityDelegateTest : SysuiTestCase() {

    @Mock private lateinit var hostView: View
    @Mock private lateinit var faceAuthInteractor: KeyguardFaceAuthInteractor
    private lateinit var underTest: FaceAuthAccessibilityDelegate

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            FaceAuthAccessibilityDelegate(
                context.resources,
                faceAuthInteractor,
            )
    }

    @Test
    fun shouldListenForFaceTrue_onInitializeAccessibilityNodeInfo_clickActionAdded() {
        whenever(faceAuthInteractor.canFaceAuthRun()).thenReturn(true)

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
    fun shouldListenForFaceFalse_onInitializeAccessibilityNodeInfo_clickActionNotAdded() {
        whenever(faceAuthInteractor.canFaceAuthRun()).thenReturn(false)

        // WHEN node is initialized
        val mockedNodeInfo = mock(AccessibilityNodeInfo::class.java)
        underTest.onInitializeAccessibilityNodeInfo(hostView, mockedNodeInfo)

        // THEN a11y action is NOT added
        verify(mockedNodeInfo, never())
            .addAction(any(AccessibilityNodeInfo.AccessibilityAction::class.java))
    }

    @Test
    fun performAccessibilityAction_actionClick_retriesFaceAuth() {
        whenever(faceAuthInteractor.canFaceAuthRun()).thenReturn(true)

        // WHEN click action is performed
        underTest.performAccessibilityAction(
            hostView,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id,
            null
        )

        verify(faceAuthInteractor).onAccessibilityAction()
    }
}

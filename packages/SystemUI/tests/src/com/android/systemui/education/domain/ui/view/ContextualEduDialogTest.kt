/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.education.domain.ui.view

import android.testing.TestableLooper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.EmptyTestActivity
import com.android.systemui.education.ui.view.ContextualEduDialog
import com.android.systemui.education.ui.viewmodel.ContextualEduToastViewModel
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.firstValue
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class ContextualEduDialogTest : SysuiTestCase() {
    @Rule
    @JvmField
    val activityRule: ActivityScenarioRule<EmptyTestActivity> =
        ActivityScenarioRule(EmptyTestActivity::class.java)
    @get:Rule val mockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var accessibilityManager: AccessibilityManager
    private lateinit var underTest: ContextualEduDialog

    @Before
    fun setUp() {
        whenever(accessibilityManager.isEnabled).thenReturn(true)
    }

    @Test
    fun sendAccessibilityInfo() {
        val message = "Testing message"
        val viewModel = ContextualEduToastViewModel(message, icon = 0, userId = 0)
        activityRule.scenario.onActivity {
            underTest = ContextualEduDialog(context, viewModel, accessibilityManager)
            underTest.show()
        }

        val eventCaptor = ArgumentCaptor.forClass(AccessibilityEvent::class.java)
        verify(accessibilityManager).sendAccessibilityEvent(eventCaptor.capture())
        assertEquals(message, eventCaptor.firstValue.text[0])
    }
}

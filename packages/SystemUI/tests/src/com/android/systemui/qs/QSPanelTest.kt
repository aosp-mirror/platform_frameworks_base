/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.qs

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper
@SmallTest
class QSPanelTest : SysuiTestCase() {
    private lateinit var mTestableLooper: TestableLooper
    private lateinit var mQsPanel: QSPanel

    private lateinit var mParentView: ViewGroup

    private lateinit var mFooter: View

    @Before
    @Throws(Exception::class)
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mTestableLooper = TestableLooper.get(this)

        mTestableLooper.runWithLooper {
            mQsPanel = QSPanel(mContext, null)
            mQsPanel.initialize()
            // QSPanel inflates a footer inside of it, mocking it here
            mFooter = LinearLayout(mContext).apply { id = R.id.qs_footer }
            mQsPanel.addView(mFooter)
            mQsPanel.onFinishInflate()
            // Provides a parent with non-zero size for QSPanel
            mParentView = FrameLayout(mContext).apply {
                addView(mQsPanel)
            }
        }
    }

    @Test
    fun testHasCollapseAccessibilityAction() {
        val info = AccessibilityNodeInfo(mQsPanel)
        mQsPanel.onInitializeAccessibilityNodeInfo(info)

        assertThat(info.actions and AccessibilityNodeInfo.ACTION_COLLAPSE).isNotEqualTo(0)
        assertThat(info.actions and AccessibilityNodeInfo.ACTION_EXPAND).isEqualTo(0)
    }

    @Test
    fun testCollapseActionCallsRunnable() {
        val mockRunnable = mock(Runnable::class.java)
        mQsPanel.setCollapseExpandAction(mockRunnable)

        mQsPanel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE, null)
        verify(mockRunnable).run()
    }
}

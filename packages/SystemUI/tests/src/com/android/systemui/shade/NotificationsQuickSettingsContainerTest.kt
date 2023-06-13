/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade

import android.testing.AndroidTestingRunner
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.QSFragment
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class NotificationsQuickSettingsContainerTest : SysuiTestCase() {

    @Mock private lateinit var qsFrame: View
    @Mock private lateinit var stackScroller: View
    @Mock private lateinit var keyguardStatusBar: View
    @Mock private lateinit var qsFragment: QSFragment

    private lateinit var qsView: ViewGroup
    private lateinit var qsContainer: View

    private lateinit var underTest: NotificationsQuickSettingsContainer

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest = NotificationsQuickSettingsContainer(context, null)

        setUpViews()
        underTest.onFinishInflate()
        underTest.onFragmentViewCreated("QS", qsFragment)
    }

    @Test
    fun qsContainerPaddingSetAgainAfterQsRecreated() {
        val padding = 100
        underTest.setQSContainerPaddingBottom(padding)

        assertThat(qsContainer.paddingBottom).isEqualTo(padding)

        // We reset the padding before "creating" a new QSFragment
        qsContainer.setPadding(0, 0, 0, 0)
        underTest.onFragmentViewCreated("QS", qsFragment)

        assertThat(qsContainer.paddingBottom).isEqualTo(padding)
    }

    private fun setUpViews() {
        qsView = FrameLayout(context)
        qsContainer = View(context)
        qsContainer.id = R.id.quick_settings_container
        qsView.addView(qsContainer)

        whenever(qsFrame.findViewById<View>(R.id.qs_frame)).thenReturn(qsFrame)
        whenever(stackScroller.findViewById<View>(R.id.notification_stack_scroller))
            .thenReturn(stackScroller)
        whenever(keyguardStatusBar.findViewById<View>(R.id.keyguard_header))
            .thenReturn(keyguardStatusBar)
        whenever(qsFragment.view).thenReturn(qsView)

        val layoutParams = ConstraintLayout.LayoutParams(0, 0)
        whenever(qsFrame.layoutParams).thenReturn(layoutParams)
        whenever(stackScroller.layoutParams).thenReturn(layoutParams)
        whenever(keyguardStatusBar.layoutParams).thenReturn(layoutParams)

        underTest.addView(qsFrame)
        underTest.addView(stackScroller)
        underTest.addView(keyguardStatusBar)
    }
}

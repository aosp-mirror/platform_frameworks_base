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

package com.android.systemui.controls.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ShapeDrawable
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow.OnDismissListener
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.EmptyTestActivity
import com.android.systemui.util.mockito.whenever
import com.android.systemui.widget.FakeListAdapter
import com.android.systemui.widget.FakeListAdapter.FakeListAdapterItem
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
open class ControlsPopupMenuTest : SysuiTestCase() {

    private companion object {

        const val DISPLAY_WIDTH_NARROW = 100
        const val DISPLAY_WIDTH_WIDE = 1000

        const val MAX_WIDTH = 380
        const val HORIZONTAL_MARGIN = 16
    }

    @Rule @JvmField val activityScenarioRule = ActivityScenarioRule(EmptyTestActivity::class.java)

    private val testDisplayMetrics = DisplayMetrics()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testDismissListenerWorks() = testPopup { activity, popupMenu ->
        popupMenu.setAdapter(FakeListAdapter())
        val listener = mock(OnDismissListener::class.java)
        popupMenu.setOnDismissListener(listener)
        popupMenu.show()

        popupMenu.dismissImmediate()

        verify(listener).onDismiss()
    }

    @Test
    fun testPopupDoesntExceedMaxWidth() = testPopup { activity, popupMenu ->
        popupMenu.setAdapter(FakeListAdapter())
        popupMenu.width = ViewGroup.LayoutParams.MATCH_PARENT
        testDisplayMetrics.widthPixels = DISPLAY_WIDTH_WIDE

        popupMenu.show()

        assertThat(popupMenu.width).isEqualTo(MAX_WIDTH)
    }

    @Test
    fun testPopupMarginsWidthLessMax() = testPopup { activity, popupMenu ->
        popupMenu.setAdapter(FakeListAdapter())
        popupMenu.width = ViewGroup.LayoutParams.MATCH_PARENT
        testDisplayMetrics.widthPixels = DISPLAY_WIDTH_NARROW

        popupMenu.show()

        assertThat(popupMenu.width).isEqualTo(DISPLAY_WIDTH_NARROW - 2 * HORIZONTAL_MARGIN)
    }

    @Test
    fun testWrapContentDoesntExceedMax() = testPopup { activity, popupMenu ->
        popupMenu.setAdapter(
            FakeListAdapter(
                listOf(
                    FakeListAdapterItem({ _, _, _ ->
                        View(activity).apply { minimumWidth = MAX_WIDTH + 1 }
                    })
                )
            )
        )
        popupMenu.width = ViewGroup.LayoutParams.WRAP_CONTENT
        testDisplayMetrics.widthPixels = DISPLAY_WIDTH_NARROW

        popupMenu.show()

        assertThat(popupMenu.width).isEqualTo(DISPLAY_WIDTH_NARROW - 2 * HORIZONTAL_MARGIN)
    }

    private fun testPopup(test: (activity: Activity, popup: ControlsPopupMenu) -> Unit) {
        activityScenarioRule.scenario.onActivity { activity ->
            val testActivity = setupActivity(activity)
            test(
                testActivity,
                ControlsPopupMenu(testActivity).apply { anchorView = View(testActivity) }
            )
        }
    }

    private fun setupActivity(real: Activity): Activity {
        val resources =
            spy(real.resources).apply {
                whenever(getDimensionPixelSize(R.dimen.control_popup_items_divider_height))
                    .thenReturn(1)
                whenever(getDimensionPixelSize(R.dimen.control_popup_horizontal_margin))
                    .thenReturn(HORIZONTAL_MARGIN)
                whenever(getDimensionPixelSize(R.dimen.control_popup_max_width))
                    .thenReturn(MAX_WIDTH)
                whenever(getDrawable(R.drawable.controls_popup_bg)).thenReturn(ShapeDrawable())
                whenever(getColor(R.color.control_popup_dim)).thenReturn(Color.WHITE)
                whenever(displayMetrics).thenAnswer { testDisplayMetrics }
            }

        return spy(real).also { whenever(it.resources).thenReturn(resources) }
    }
}

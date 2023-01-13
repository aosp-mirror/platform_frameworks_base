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

package com.android.systemui.util.view

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`

@SmallTest
class ViewUtilTest : SysuiTestCase() {
    private val viewUtil = ViewUtil()
    private lateinit var view: View

    @Before
    fun setUp() {
        view = TextView(context)
        view.setLeftTopRightBottom(VIEW_LEFT, VIEW_TOP, VIEW_RIGHT, VIEW_BOTTOM)

        view = spy(view)
        val location = IntArray(2)
        location[0] = VIEW_LEFT
        location[1] = VIEW_TOP
        `when`(view.locationOnScreen).thenReturn(location)
        doAnswer { invocation ->
            val pos = invocation.arguments[0] as IntArray
            pos[0] = VIEW_LEFT
            pos[1] = VIEW_TOP
            null
        }.`when`(view).getLocationInWindow(any())
    }

    @Test
    fun touchIsWithinView_inBounds_returnsTrue() {
        assertThat(viewUtil.touchIsWithinView(view, VIEW_LEFT + 1f, VIEW_TOP + 1f)).isTrue()
    }

    @Test
    fun touchIsWithinView_onTopLeftCorner_returnsTrue() {
        assertThat(viewUtil.touchIsWithinView(
            view, VIEW_LEFT.toFloat(), VIEW_TOP.toFloat())
        ).isTrue()
    }

    @Test
    fun touchIsWithinView_onBottomRightCorner_returnsTrue() {
        assertThat(viewUtil.touchIsWithinView(view, VIEW_RIGHT.toFloat(), VIEW_BOTTOM.toFloat()))
            .isTrue()
    }

    @Test
    fun touchIsWithinView_xTooSmall_returnsFalse() {
        assertThat(viewUtil.touchIsWithinView(view, VIEW_LEFT - 1f, VIEW_TOP + 1f)).isFalse()
    }

    @Test
    fun touchIsWithinView_xTooLarge_returnsFalse() {
        assertThat(viewUtil.touchIsWithinView(view, VIEW_RIGHT + 1f, VIEW_TOP + 1f)).isFalse()
    }

    @Test
    fun touchIsWithinView_yTooSmall_returnsFalse() {
        assertThat(viewUtil.touchIsWithinView(view, VIEW_LEFT + 1f, VIEW_TOP - 1f)).isFalse()
    }

    @Test
    fun touchIsWithinView_yTooLarge_returnsFalse() {
        assertThat(viewUtil.touchIsWithinView(view, VIEW_LEFT + 1f, VIEW_BOTTOM + 1f)).isFalse()
    }

    @Test
    fun setRectToViewWindowLocation_rectHasLocation() {
        val outRect = Rect()

        viewUtil.setRectToViewWindowLocation(view, outRect)

        assertThat(outRect.left).isEqualTo(VIEW_LEFT)
        assertThat(outRect.right).isEqualTo(VIEW_RIGHT)
        assertThat(outRect.top).isEqualTo(VIEW_TOP)
        assertThat(outRect.bottom).isEqualTo(VIEW_BOTTOM)
    }
}

private const val VIEW_LEFT = 30
private const val VIEW_RIGHT = 100
private const val VIEW_TOP = 40
private const val VIEW_BOTTOM = 100

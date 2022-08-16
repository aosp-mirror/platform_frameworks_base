package com.android.systemui.util.view

import android.view.View
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
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
}

private const val VIEW_LEFT = 30
private const val VIEW_RIGHT = 100
private const val VIEW_TOP = 40
private const val VIEW_BOTTOM = 100

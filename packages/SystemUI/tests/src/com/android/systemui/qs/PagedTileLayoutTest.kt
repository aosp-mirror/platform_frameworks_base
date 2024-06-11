package com.android.systemui.qs

import android.content.Context
import android.view.View
import android.widget.Scroller
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.PageIndicator.PageScrollActionListener
import com.android.systemui.qs.PageIndicator.PageScrollActionListener.LEFT
import com.android.systemui.qs.PageIndicator.PageScrollActionListener.RIGHT
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
class PagedTileLayoutTest : SysuiTestCase() {

    @Mock private lateinit var pageIndicator: PageIndicator
    @Captor private lateinit var captor: ArgumentCaptor<PageScrollActionListener>

    private lateinit var pageTileLayout: TestPagedTileLayout
    private lateinit var scroller: Scroller

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        pageTileLayout = TestPagedTileLayout(mContext)
        pageTileLayout.setPageIndicator(pageIndicator)
        verify(pageIndicator).setPageScrollActionListener(captor.capture())
        setViewWidth(pageTileLayout, width = PAGE_WIDTH)
        scroller = pageTileLayout.mScroller
    }

    private fun setViewWidth(view: View, width: Int) {
        view.left = 0
        view.right = width
    }

    @Test
    fun scrollsRight_afterRightScrollActionTriggered() {
        pageTileLayout.currentPageIndex = 0

        sendScrollActionEvent(RIGHT)

        assertThat(scroller.isFinished).isFalse() // aka we're scrolling
        assertThat(scroller.finalX).isEqualTo(scroller.currX + PAGE_WIDTH)
    }

    @Test
    fun scrollsLeft_afterLeftScrollActionTriggered() {
        pageTileLayout.currentPageIndex = 1 // we won't scroll left if we're on the first page

        sendScrollActionEvent(LEFT)

        assertThat(scroller.isFinished).isFalse() // aka we're scrolling
        assertThat(scroller.finalX).isEqualTo(scroller.currX - PAGE_WIDTH)
    }

    private fun sendScrollActionEvent(@PageScrollActionListener.Direction direction: Int) {
        captor.value.onScrollActionTriggered(direction)
    }

    /**
     * Custom PagedTileLayout to easy mock "currentItem" i.e. currently visible page. Setting this
     * up otherwise would require setting adapter etc
     */
    class TestPagedTileLayout(context: Context) : PagedTileLayout(context, null) {

        var currentPageIndex: Int = 0

        override fun getCurrentItem(): Int {
            return currentPageIndex
        }
    }

    companion object {
        const val PAGE_WIDTH = 200
    }
}

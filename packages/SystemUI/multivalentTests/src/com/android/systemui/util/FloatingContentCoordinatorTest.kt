package com.android.systemui.util

import android.graphics.Rect
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.wm.shell.common.FloatingContentCoordinator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4::class)
@SmallTest
class FloatingContentCoordinatorTest : SysuiTestCase() {

    private val screenBounds = Rect(0, 0, 1000, 1000)

    private val rect100px = Rect()
    private val rect100pxFloating = FloatingRect(rect100px)

    private val rect200px = Rect()
    private val rect200pxFloating = FloatingRect(rect200px)

    private val rect300px = Rect()
    private val rect300pxFloating = FloatingRect(rect300px)

    private val floatingCoordinator = FloatingContentCoordinator()

    @Before
    fun setup() {
        rect100px.set(0, 0, 100, 100)
        rect200px.set(0, 0, 200, 200)
        rect300px.set(0, 0, 300, 300)
    }

    @After
    fun tearDown() {
        // We need to remove this stuff since it's a singleton object and it'll be there for the
        // next test.
        floatingCoordinator.onContentRemoved(rect100pxFloating)
        floatingCoordinator.onContentRemoved(rect200pxFloating)
        floatingCoordinator.onContentRemoved(rect300pxFloating)
    }

    @Test
    fun testOnContentAdded() {
        // Add rect1, and verify that the coordinator didn't move it.
        floatingCoordinator.onContentAdded(rect100pxFloating)
        assertEquals(rect100px.top, 0)

        // Add rect2, which intersects rect1. Verify that rect2 was not moved, since newly added
        // content is allowed to remain where it is. rect1 should have been moved below rect2
        // since it was in the way.
        floatingCoordinator.onContentAdded(rect200pxFloating)
        assertEquals(rect200px.top, 0)
        assertEquals(rect100px.top, 200)

        verifyRectSizes()
    }

    @Test
    fun testOnContentRemoved() {
        // Add rect1, and remove it. Then add rect2. Since rect1 was removed before that, it should
        // no longer be considered in the way, so it shouldn't move when rect2 is added.
        floatingCoordinator.onContentAdded(rect100pxFloating)
        floatingCoordinator.onContentRemoved(rect100pxFloating)
        floatingCoordinator.onContentAdded(rect200pxFloating)

        assertEquals(rect100px.top, 0)
        assertEquals(rect200px.top, 0)

        verifyRectSizes()
    }

    @Test
    fun testOnContentMoved_twoRects() {
        // Add rect1, which is at y = 0.
        floatingCoordinator.onContentAdded(rect100pxFloating)

        // Move rect2 down to 500px, where it won't conflict with rect1.
        rect200px.offsetTo(0, 500)
        floatingCoordinator.onContentAdded(rect200pxFloating)

        // Then, move it to 0px where it will absolutely conflict with rect1.
        rect200px.offsetTo(0, 0)
        floatingCoordinator.onContentMoved(rect200pxFloating)

        // The coordinator should have left rect2 alone, and moved rect1 below it. rect1 should now
        // be at y = 200.
        assertEquals(rect200px.top, 0)
        assertEquals(rect100px.top, 200)

        verifyRectSizes()

        // Move rect2 to y = 275px. Since this puts it at the bottom half of rect1, it should push
        // rect1 upward and leave rect2 alone.
        rect200px.offsetTo(0, 275)
        floatingCoordinator.onContentMoved(rect200pxFloating)

        assertEquals(rect200px.top, 275)
        assertEquals(rect100px.top, 175)

        verifyRectSizes()

        // Move rect2 to y = 110px. This makes it intersect rect1 again, but above its center of
        // mass. That means rect1 should be pushed downward.
        rect200px.offsetTo(0, 110)
        floatingCoordinator.onContentMoved(rect200pxFloating)

        assertEquals(rect200px.top, 110)
        assertEquals(rect100px.top, 310)

        verifyRectSizes()
    }

    @Test
    fun testOnContentMoved_threeRects() {
        floatingCoordinator.onContentAdded(rect100pxFloating)

        // Add rect2, which should displace rect1 to y = 200
        floatingCoordinator.onContentAdded(rect200pxFloating)
        assertEquals(rect200px.top, 0)
        assertEquals(rect100px.top, 200)

        // Add rect3, which should completely cover both rect1 and rect2. That should cause them to
        // move away. The order in which they do so is non-deterministic, so just make sure none of
        // the three Rects intersect.
        floatingCoordinator.onContentAdded(rect300pxFloating)

        assertFalse(Rect.intersects(rect100px, rect200px))
        assertFalse(Rect.intersects(rect100px, rect300px))
        assertFalse(Rect.intersects(rect200px, rect300px))

        // Move rect2 to intersect both rect1 and rect3.
        rect200px.offsetTo(0, 150)
        floatingCoordinator.onContentMoved(rect200pxFloating)

        assertFalse(Rect.intersects(rect100px, rect200px))
        assertFalse(Rect.intersects(rect100px, rect300px))
        assertFalse(Rect.intersects(rect200px, rect300px))
    }

    @Test
    fun testOnContentMoved_respectsUpperBounds() {
        // Add rect1, which is at y = 0.
        floatingCoordinator.onContentAdded(rect100pxFloating)

        // Move rect2 down to 500px, where it won't conflict with rect1.
        rect200px.offsetTo(0, 500)
        floatingCoordinator.onContentAdded(rect200pxFloating)

        // Then, move it to 90px where it will conflict with rect1, but with a center of mass below
        // that of rect1's. This would normally mean that rect1 moves upward. However, since it's at
        // the top of the screen, it should go downward instead.
        rect200px.offsetTo(0, 90)
        floatingCoordinator.onContentMoved(rect200pxFloating)

        // rect2 should have been left alone, rect1 is now below rect2 at y = 290px even though it
        // was intersected from below.
        assertEquals(rect200px.top, 90)
        assertEquals(rect100px.top, 290)
    }

    @Test
    fun testOnContentMoved_respectsLowerBounds() {
        // Put rect1 at the bottom of the screen and add it.
        rect100px.offsetTo(0, screenBounds.bottom - 100)
        floatingCoordinator.onContentAdded(rect100pxFloating)

        // Put rect2 at the bottom as well. Since its center of mass is above rect1's, rect1 would
        // normally move downward. Since it's at the bottom of the screen, it should go upward
        // instead.
        rect200px.offsetTo(0, 800)
        floatingCoordinator.onContentAdded(rect200pxFloating)

        assertEquals(rect200px.top, 800)
        assertEquals(rect100px.top, 700)
    }

    /**
     * Tests that the rect sizes didn't change when the coordinator manipulated them. This allows us
     * to assert only the value of rect.top in tests, since if top, width, and height are correct,
     * that means top/left/right/bottom are all correct.
     */
    private fun verifyRectSizes() {
        assertEquals(100, rect100px.width())
        assertEquals(200, rect200px.width())
        assertEquals(300, rect300px.width())

        assertEquals(100, rect100px.height())
        assertEquals(200, rect200px.height())
        assertEquals(300, rect300px.height())
    }

    /**
     * Helper class that uses [floatingCoordinator.findAreaForContentVertically] to move a Rect when
     * needed.
     */
    inner class FloatingRect(private val underlyingRect: Rect) :
        FloatingContentCoordinator.FloatingContent {
        override fun moveToBounds(bounds: Rect) {
            underlyingRect.set(bounds)
        }

        override fun getAllowedFloatingBoundsRegion(): Rect {
            return screenBounds
        }

        override fun getFloatingBoundsOnScreen(): Rect {
            return underlyingRect
        }
    }
}

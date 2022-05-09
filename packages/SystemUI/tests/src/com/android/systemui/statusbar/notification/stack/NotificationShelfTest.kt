package com.android.systemui.statusbar.notification.stack

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.NotificationShelf
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.mockito.Mockito.`when` as whenever

/**
 * Tests for {@link NotificationShelf}.
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class NotificationShelfTest : SysuiTestCase() {

    private val shelf = NotificationShelf(context, /* attrs */ null)
    private val shelfState = shelf.viewState as NotificationShelf.ShelfState
    private val ambientState = mock(AmbientState::class.java)

    @Before
    fun setUp() {
        shelf.bind(ambientState, /* hostLayoutController */ null)
        shelf.layout(/* left */ 0, /* top */ 0, /* right */ 30, /* bottom */5)
    }

    @Test
    fun testShadeWidth_BasedOnFractionToShade() {
        setFractionToShade(0f)
        setOnLockscreen(true)

        shelf.updateActualWidth(/* fractionToShade */ 0f, /* shortestWidth */ 10f);
        assertTrue(shelf.actualWidth == 10)

        shelf.updateActualWidth(/* fractionToShade */ 0.5f, /* shortestWidth */ 10f)
        assertTrue(shelf.actualWidth == 20)

        shelf.updateActualWidth(/* fractionToShade */ 1f, /* shortestWidth */ 10f)
        assertTrue(shelf.actualWidth == 30)
    }

    @Test
    fun testShelfIsLong_WhenNotOnLockscreen() {
        setFractionToShade(0f)
        setOnLockscreen(false)

        shelf.updateActualWidth(/* fraction */ 0f, /* shortestWidth */ 10f)
        assertTrue(shelf.actualWidth == 30)
    }

    @Test
    fun testX_inViewForClick() {
        val isXInView = shelf.isXInView(
                /* localX */ 5f,
                /* slop */ 5f,
                /* left */ 0f,
                /* right */ 10f)
        assertTrue(isXInView)
    }

    @Test
    fun testXSlop_inViewForClick() {
        val isLeftXSlopInView = shelf.isXInView(
                /* localX */ -3f,
                /* slop */ 5f,
                /* left */ 0f,
                /* right */ 10f)
        assertTrue(isLeftXSlopInView)

        val isRightXSlopInView = shelf.isXInView(
                /* localX */ 13f,
                /* slop */ 5f,
                /* left */ 0f,
                /* right */ 10f)
        assertTrue(isRightXSlopInView)
    }

    @Test
    fun testX_notInViewForClick() {
        val isXLeftOfShelfInView = shelf.isXInView(
                /* localX */ -10f,
                /* slop */ 5f,
                /* left */ 0f,
                /* right */ 10f)
        assertFalse(isXLeftOfShelfInView)

        val isXRightOfShelfInView = shelf.isXInView(
                /* localX */ 20f,
                /* slop */ 5f,
                /* left */ 0f,
                /* right */ 10f)
        assertFalse(isXRightOfShelfInView)
    }

    @Test
    fun testY_inViewForClick() {
        val isYInView = shelf.isYInView(
                /* localY */ 5f,
                /* slop */ 5f,
                /* top */ 0f,
                /* bottom */ 10f)
        assertTrue(isYInView)
    }

    @Test
    fun testYSlop_inViewForClick() {
        val isTopYSlopInView = shelf.isYInView(
                /* localY */ -3f,
                /* slop */ 5f,
                /* top */ 0f,
                /* bottom */ 10f)
        assertTrue(isTopYSlopInView)

        val isBottomYSlopInView = shelf.isYInView(
                /* localY */ 13f,
                /* slop */ 5f,
                /* top */ 0f,
                /* bottom */ 10f)
        assertTrue(isBottomYSlopInView)
    }

    @Test
    fun testY_notInViewForClick() {
        val isYAboveShelfInView = shelf.isYInView(
                /* localY */ -10f,
                /* slop */ 5f,
                /* top */ 0f,
                /* bottom */ 5f)
        assertFalse(isYAboveShelfInView)

        val isYBelowShelfInView = shelf.isYInView(
                /* localY */ 15f,
                /* slop */ 5f,
                /* top */ 0f,
                /* bottom */ 5f)
        assertFalse(isYBelowShelfInView)
    }

    private fun setFractionToShade(fraction: Float) {
        whenever(ambientState.fractionToShade).thenReturn(fraction)
    }

    private fun setOnLockscreen(isOnLockscreen: Boolean) {
        whenever(ambientState.isOnKeyguard).thenReturn(isOnLockscreen)
    }
}
package com.android.systemui.animation

import android.animation.ObjectAnimator
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertNull
import junit.framework.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class
ViewHierarchyAnimatorTest : SysuiTestCase() {
    companion object {
        private const val TEST_DURATION = 1000L
        private val TEST_INTERPOLATOR = Interpolators.LINEAR
    }

    private val childParams = LinearLayout.LayoutParams(
        0 /* width */,
        LinearLayout.LayoutParams.MATCH_PARENT
    )
    private lateinit var rootView: LinearLayout

    @Before
    fun setUp() {
        rootView = LinearLayout(mContext)
        rootView.orientation = LinearLayout.HORIZONTAL
        rootView.weightSum = 1f
        childParams.weight = 0.5f
    }

    @After
    fun tearDown() {
        endAnimation(rootView)
        ViewHierarchyAnimator.stopAnimating(rootView)
    }

    @Test
    fun respectsAnimationParameters() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        // animate()
        var success = ViewHierarchyAnimator.animate(
            rootView, interpolator = TEST_INTERPOLATOR, duration = TEST_DURATION
        )
        rootView.layout(0 /* l */, 0 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        var animator = rootView.getTag(R.id.tag_animator) as ObjectAnimator
        assertEquals(animator.interpolator, TEST_INTERPOLATOR)
        assertEquals(animator.duration, TEST_DURATION)

        endAnimation(rootView)
        ViewHierarchyAnimator.stopAnimating(rootView)

        // animateNextUpdate()
        success = ViewHierarchyAnimator.animateNextUpdate(
            rootView, interpolator = TEST_INTERPOLATOR, duration = TEST_DURATION
        )
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        animator = rootView.getTag(R.id.tag_animator) as ObjectAnimator
        assertEquals(animator.interpolator, TEST_INTERPOLATOR)
        assertEquals(animator.duration, TEST_DURATION)

        endAnimation(rootView)

        // animateAddition()
        rootView.layout(0 /* l */, 0 /* t */, 0 /* r */, 0 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView, interpolator = TEST_INTERPOLATOR, duration = TEST_DURATION
        )
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        animator = rootView.getTag(R.id.tag_animator) as ObjectAnimator
        assertEquals(animator.interpolator, TEST_INTERPOLATOR)
        assertEquals(animator.duration, TEST_DURATION)
    }

    @Test
    fun animatesFromStartToEnd() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        val success = ViewHierarchyAnimator.animate(rootView)
        // Change all bounds.
        rootView.layout(0 /* l */, 15 /* t */, 70 /* r */, 80 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        // The initial values should be those of the previous layout.
        checkBounds(rootView, l = 10, t = 10, r = 50, b = 50)
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        // The end values should be those of the latest layout.
        checkBounds(rootView, l = 0, t = 15, r = 70, b = 80)
    }

    @Test
    fun animatesSuccessiveLayoutChanges() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        val success = ViewHierarchyAnimator.animate(rootView)
        // Change all bounds.
        rootView.layout(0 /* l */, 15 /* t */, 70 /* r */, 80 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 15, r = 70, b = 80)

        // Change only top and right.
        rootView.layout(0 /* l */, 20 /* t */, 60 /* r */, 80 /* b */)

        assertNotNull(rootView.getTag(R.id.tag_animator))
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 20, r = 60, b = 80)

        // Change all bounds again.
        rootView.layout(5 /* l */, 25 /* t */, 55 /* r */, 95 /* b */)

        assertNotNull(rootView.getTag(R.id.tag_animator))
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 5, t = 25, r = 55, b = 95)
    }

    @Test
    fun animatesFromPreviousAnimationProgress() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        val success =
            ViewHierarchyAnimator.animateNextUpdate(rootView, interpolator = TEST_INTERPOLATOR)
        // Change all bounds.
        rootView.layout(0 /* l */, 20 /* t */, 70 /* r */, 80 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        advanceAnimation(rootView, fraction = 0.5f)
        checkBounds(rootView, l = 5, t = 15, r = 60, b = 65)

        // Change all bounds again.
        rootView.layout(25 /* l */, 25 /* t */, 55 /* r */, 60 /* b */)

        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 5, t = 15, r = 60, b = 65)
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 25, t = 25, r = 55, b = 60)
    }

    @Test
    fun animatesRootAndChildren() {
        val firstChild = View(mContext)
        firstChild.layoutParams = childParams
        rootView.addView(firstChild)
        val secondChild = View(mContext)
        secondChild.layoutParams = childParams
        rootView.addView(secondChild)
        rootView.measure(
            View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        rootView.layout(0 /* l */, 0 /* t */, 150 /* r */, 100 /* b */)

        val success = ViewHierarchyAnimator.animate(rootView)
        // Change all bounds.
        rootView.measure(
            View.MeasureSpec.makeMeasureSpec(190, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        rootView.layout(10 /* l */, 20 /* t */, 200 /* r */, 120 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        assertNotNull(firstChild.getTag(R.id.tag_animator))
        assertNotNull(secondChild.getTag(R.id.tag_animator))
        // The initial values should be those of the previous layout.
        checkBounds(rootView, l = 0, t = 0, r = 150, b = 100)
        checkBounds(firstChild, l = 0, t = 0, r = 75, b = 100)
        checkBounds(secondChild, l = 75, t = 0, r = 150, b = 100)
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        assertNull(firstChild.getTag(R.id.tag_animator))
        assertNull(secondChild.getTag(R.id.tag_animator))
        // The end values should be those of the latest layout.
        checkBounds(rootView, l = 10, t = 20, r = 200, b = 120)
        checkBounds(firstChild, l = 0, t = 0, r = 95, b = 100)
        checkBounds(secondChild, l = 95, t = 0, r = 190, b = 100)
    }

    @Test
    fun animatesAppearingViewsFromStartToEnd() {
        // Starting GONE.
        rootView.visibility = View.GONE
        rootView.layout(0 /* l */, 50 /* t */, 50 /* r */, 100 /* b */)
        var success = ViewHierarchyAnimator.animateAddition(rootView)
        rootView.visibility = View.VISIBLE
        rootView.layout(0 /* l */, 100 /* t */, 100 /* r */, 200 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 50, t = 150, r = 50, b = 150)
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 100, r = 100, b = 200)

        // Starting INVISIBLE.
        rootView.visibility = View.INVISIBLE
        rootView.layout(0 /* l */, 50 /* t */, 50 /* r */, 100 /* b */)
        success = ViewHierarchyAnimator.animateAddition(rootView)
        rootView.visibility = View.VISIBLE
        rootView.layout(0 /* l */, 100 /* t */, 100 /* r */, 200 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 50, t = 150, r = 50, b = 150)
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 100, r = 100, b = 200)

        // Starting with nothing.
        rootView.layout(0 /* l */, 0 /* t */, 0 /* r */, 0 /* b */)
        success = ViewHierarchyAnimator.animateAddition(rootView)
        rootView.layout(0 /* l */, 20 /* t */, 50 /* r */, 80 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 25, t = 50, r = 25, b = 50)
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 20, r = 50, b = 80)

        // Starting with 0 width.
        rootView.layout(0 /* l */, 50 /* t */, 0 /* r */, 100 /* b */)
        success = ViewHierarchyAnimator.animateAddition(rootView)
        rootView.layout(0 /* l */, 50 /* t */, 50 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 25, t = 75, r = 25, b = 75)
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 50, r = 50, b = 100)

        // Starting with 0 height.
        rootView.layout(0 /* l */, 50 /* t */, 50 /* r */, 50 /* b */)
        success = ViewHierarchyAnimator.animateAddition(rootView)
        rootView.layout(0 /* l */, 50 /* t */, 50 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 25, t = 75, r = 25, b = 75)
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 50, r = 50, b = 100)
    }

    @Test
    fun animatesAppearingViewsRespectingOrigin() {
        // CENTER.
        rootView.layout(0 /* l */, 0 /* t */, 0 /* r */, 0 /* b */)
        var success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.CENTER
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 75, t = 75, r = 75, b = 75)
        endAnimation(rootView)

        // LEFT.
        rootView.layout(0 /* l */, 0 /* t */, 0 /* r */, 0 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.LEFT
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 50, t = 50, r = 50, b = 100)
        endAnimation(rootView)

        // TOP_LEFT.
        rootView.layout(0 /* l */, 0 /* t */, 0 /* r */, 0 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.TOP_LEFT
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 50, t = 50, r = 50, b = 50)
        endAnimation(rootView)

        // TOP.
        rootView.layout(150 /* l */, 0 /* t */, 150 /* r */, 0 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.TOP
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 50, t = 50, r = 100, b = 50)
        endAnimation(rootView)

        // TOP_RIGHT.
        rootView.layout(150 /* l */, 0 /* t */, 150 /* r */, 0 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.TOP_RIGHT
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 100, t = 50, r = 100, b = 50)
        endAnimation(rootView)

        // RIGHT.
        rootView.layout(150 /* l */, 150 /* t */, 150 /* r */, 150 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.RIGHT
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 100, t = 50, r = 100, b = 100)
        endAnimation(rootView)

        // BOTTOM_RIGHT.
        rootView.layout(150 /* l */, 150 /* t */, 150 /* r */, 150 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.BOTTOM_RIGHT
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 100, t = 100, r = 100, b = 100)
        endAnimation(rootView)

        // BOTTOM.
        rootView.layout(0 /* l */, 150 /* t */, 0 /* r */, 150 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.BOTTOM
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 50, t = 100, r = 100, b = 100)
        endAnimation(rootView)

        // BOTTOM_LEFT.
        rootView.layout(0 /* l */, 150 /* t */, 0 /* r */, 150 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.BOTTOM_LEFT
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 50, t = 100, r = 50, b = 100)
        endAnimation(rootView)
    }

    @Test
    fun animatesAppearingViewsRespectingMargins() {
        // CENTER.
        rootView.layout(0 /* l */, 0 /* t */, 0 /* r */, 0 /* b */)
        var success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.CENTER,
            includeMargins = true
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 75, t = 75, r = 75, b = 75)
        endAnimation(rootView)

        // LEFT.
        rootView.layout(0 /* l */, 0 /* t */, 0 /* r */, 0 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView, origin = ViewHierarchyAnimator.Hotspot.LEFT,
            includeMargins = true
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 50, r = 0, b = 100)
        endAnimation(rootView)

        // TOP_LEFT.
        rootView.layout(0 /* l */, 0 /* t */, 0 /* r */, 0 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.TOP_LEFT,
            includeMargins = true
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 0, r = 0, b = 0)
        endAnimation(rootView)

        // TOP.
        rootView.layout(150 /* l */, 0 /* t */, 150 /* r */, 0 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView, origin = ViewHierarchyAnimator.Hotspot.TOP,
            includeMargins = true
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 50, t = 0, r = 100, b = 0)
        endAnimation(rootView)

        // TOP_RIGHT.
        rootView.layout(150 /* l */, 0 /* t */, 150 /* r */, 0 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.TOP_RIGHT,
            includeMargins = true
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 150, t = 0, r = 150, b = 0)
        endAnimation(rootView)

        // RIGHT.
        rootView.layout(150 /* l */, 150 /* t */, 150 /* r */, 150 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.RIGHT,
            includeMargins = true
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 150, t = 50, r = 150, b = 100)
        endAnimation(rootView)

        // BOTTOM_RIGHT.
        rootView.layout(150 /* l */, 150 /* t */, 150 /* r */, 150 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.BOTTOM_RIGHT,
            includeMargins = true
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 150, t = 150, r = 150, b = 150)
        endAnimation(rootView)

        // BOTTOM.
        rootView.layout(0 /* l */, 150 /* t */, 0 /* r */, 150 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.BOTTOM,
            includeMargins = true
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 50, t = 150, r = 100, b = 150)
        endAnimation(rootView)

        // BOTTOM_LEFT.
        rootView.layout(0 /* l */, 150 /* t */, 0 /* r */, 150 /* b */)
        success = ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.BOTTOM_LEFT,
            includeMargins = true
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 150, r = 0, b = 150)
        endAnimation(rootView)
    }

    @Test
    fun doesNotAnimateInvisibleViews() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        // GONE
        rootView.visibility = View.GONE
        var success = ViewHierarchyAnimator.animate(rootView)
        rootView.layout(0 /* l */, 15 /* t */, 55 /* r */, 80 /* b */)

        assertFalse(success)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 15, r = 55, b = 80)

        // INVISIBLE.
        rootView.visibility = View.INVISIBLE
        success = ViewHierarchyAnimator.animate(rootView)
        rootView.layout(0 /* l */, 20 /* t */, 10 /* r */, 50 /* b */)

        assertFalse(success)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 20, r = 10, b = 50)
    }

    @Test
    fun doesNotAnimateAppearingViews() {
        // Starting with nothing.
        rootView.layout(0 /* l */, 0 /* t */, 0 /* r */, 0 /* b */)
        var success = ViewHierarchyAnimator.animate(rootView)
        rootView.layout(0 /* l */, 15 /* t */, 55 /* r */, 80 /* b */)

        assertFalse(success)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 15, r = 55, b = 80)

        // Starting with 0 width.
        rootView.layout(0 /* l */, 50 /* t */, 0 /* r */, 100 /* b */)
        success = ViewHierarchyAnimator.animate(rootView)
        rootView.layout(0 /* l */, 15 /* t */, 55 /* r */, 80 /* b */)

        assertFalse(success)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 15, r = 55, b = 80)

        // Starting with 0 height.
        rootView.layout(0 /* l */, 50 /* t */, 50 /* r */, 50 /* b */)
        success = ViewHierarchyAnimator.animate(rootView)
        rootView.layout(0 /* l */, 15 /* t */, 55 /* r */, 80 /* b */)

        assertFalse(success)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 15, r = 55, b = 80)
    }

    @Test
    fun doesNotAnimateDisappearingViews() {
        rootView.layout(0 /* l */, 0 /* t */, 100 /* r */, 100 /* b */)

        val success = ViewHierarchyAnimator.animate(rootView)
        // Ending with nothing.
        rootView.layout(0 /* l */, 0 /* t */, 0 /* r */, 0 /* b */)

        assertTrue(success)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 0, r = 0, b = 0)

        // Ending with 0 width.
        rootView.layout(0 /* l */, 50 /* t */, 50 /* r */, 100 /* b */)
        endAnimation(rootView)
        rootView.layout(0 /* l */, 15 /* t */, 0 /* r */, 80 /* b */)

        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 15, r = 0, b = 80)

        // Ending with 0 height.
        rootView.layout(0 /* l */, 50 /* t */, 50 /* r */, 100 /* b */)
        endAnimation(rootView)
        rootView.layout(0 /* l */, 50 /* t */, 55 /* r */, 50 /* b */)

        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 50, r = 55, b = 50)
    }

    @Test
    fun doesNotAnimateUnchangingBounds() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        val success = ViewHierarchyAnimator.animate(rootView)
        // No bounds are changed.
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        assertTrue(success)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 10, t = 10, r = 50, b = 50)

        // Change only right and bottom.
        rootView.layout(10 /* l */, 10 /* t */, 70 /* r */, 80 /* b */)

        assertNotNull(rootView.getTag(R.id.tag_animator))
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 10, t = 10, r = 70, b = 80)
    }

    @Test
    fun stopsAnimatingAfterSingleLayout() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        val success = ViewHierarchyAnimator.animateNextUpdate(rootView)
        // Change all bounds.
        rootView.layout(0 /* l */, 15 /* t */, 70 /* r */, 80 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 15, r = 70, b = 80)

        // Change all bounds again.
        rootView.layout(10 /* l */, 10 /* t */, 50/* r */, 50 /* b */)

        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 10, t = 10, r = 50, b = 50)
    }

    @Test
    fun stopsAnimatingWhenInstructed() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        val success = ViewHierarchyAnimator.animate(rootView)
        // Change all bounds.
        rootView.layout(0 /* l */, 15 /* t */, 70 /* r */, 80 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 15, r = 70, b = 80)

        ViewHierarchyAnimator.stopAnimating(rootView)
        // Change all bounds again.
        rootView.layout(10 /* l */, 10 /* t */, 50/* r */, 50 /* b */)

        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 10, t = 10, r = 50, b = 50)
    }

    private fun checkBounds(v: View, l: Int, t: Int, r: Int, b: Int) {
        assertEquals(l, v.left)
        assertEquals(t, v.top)
        assertEquals(r, v.right)
        assertEquals(b, v.bottom)
    }

    private fun advanceAnimation(rootView: View, fraction: Float) {
        (rootView.getTag(R.id.tag_animator) as? ObjectAnimator)?.setCurrentFraction(fraction)

        if (rootView is ViewGroup) {
            for (i in 0 until rootView.childCount) {
                advanceAnimation(rootView.getChildAt(i), fraction)
            }
        }
    }

    private fun endAnimation(rootView: View) {
        (rootView.getTag(R.id.tag_animator) as? ObjectAnimator)?.end()

        if (rootView is ViewGroup) {
            for (i in 0 until rootView.childCount) {
                endAnimation(rootView.getChildAt(i))
            }
        }
    }
}

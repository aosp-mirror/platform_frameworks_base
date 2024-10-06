package com.android.systemui.animation

import android.animation.ObjectAnimator
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.app.animation.Interpolators
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.children
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
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class
ViewHierarchyAnimatorTest : SysuiTestCase() {
    companion object {
        private const val TEST_DURATION = 1000L
        private val TEST_INTERPOLATOR = Interpolators.LINEAR
    }

    private lateinit var rootView: ViewGroup

    @Before
    fun setUp() {
        rootView = LinearLayout(mContext)
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

        // animateRemoval()
        setUpRootWithChildren()
        val child = rootView.getChildAt(0)
        success = ViewHierarchyAnimator.animateRemoval(
            child, interpolator = TEST_INTERPOLATOR, duration = TEST_DURATION
        )

        assertTrue(success)
        assertNotNull(child.getTag(R.id.tag_animator))
        animator = child.getTag(R.id.tag_animator) as ObjectAnimator
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
    fun animatesRootAndChildren_withoutExcludedViews() {
        setUpRootWithChildren()

        val success = ViewHierarchyAnimator.animate(rootView)
        // Change all bounds.
        rootView.measure(
            View.MeasureSpec.makeMeasureSpec(190, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        rootView.layout(10 /* l */, 20 /* t */, 200 /* r */, 120 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        assertNotNull(rootView.getChildAt(0).getTag(R.id.tag_animator))
        assertNotNull(rootView.getChildAt(1).getTag(R.id.tag_animator))
        // The initial values should be those of the previous layout.
        checkBounds(rootView, l = 0, t = 0, r = 200, b = 100)
        checkBounds(rootView.getChildAt(0), l = 0, t = 0, r = 100, b = 100)
        checkBounds(rootView.getChildAt(1), l = 100, t = 0, r = 200, b = 100)
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        assertNull(rootView.getChildAt(0).getTag(R.id.tag_animator))
        assertNull(rootView.getChildAt(1).getTag(R.id.tag_animator))
        // The end values should be those of the latest layout.
        checkBounds(rootView, l = 10, t = 20, r = 200, b = 120)
        checkBounds(rootView.getChildAt(0), l = 0, t = 0, r = 95, b = 100)
        checkBounds(rootView.getChildAt(1), l = 95, t = 0, r = 190, b = 100)
    }

    @Test
    fun animatesRootAndChildren_withExcludedViews() {
        setUpRootWithChildren()

        val success = ViewHierarchyAnimator.animate(
            rootView,
            excludedViews = setOf(rootView.getChildAt(0))
        )
        // Change all bounds.
        rootView.measure(
                View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        rootView.layout(10 /* l */, 20 /* t */, 200 /* r */, 120 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        assertNull(rootView.getChildAt(0).getTag(R.id.tag_animator))
        assertNotNull(rootView.getChildAt(1).getTag(R.id.tag_animator))
        // The initial values for the affected views should be those of the previous layout, while
        // the excluded view should be at the final values from the beginning.
        checkBounds(rootView, l = 0, t = 0, r = 200, b = 100)
        checkBounds(rootView.getChildAt(0), l = 0, t = 0, r = 90, b = 100)
        checkBounds(rootView.getChildAt(1), l = 100, t = 0, r = 200, b = 100)
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        assertNull(rootView.getChildAt(0).getTag(R.id.tag_animator))
        assertNull(rootView.getChildAt(1).getTag(R.id.tag_animator))
        // The end values should be those of the latest layout.
        checkBounds(rootView, l = 10, t = 20, r = 200, b = 120)
        checkBounds(rootView.getChildAt(0), l = 0, t = 0, r = 90, b = 100)
        checkBounds(rootView.getChildAt(1), l = 90, t = 0, r = 180, b = 100)
    }

    @Test
    fun animatesRootOnly() {
        setUpRootWithChildren()

        val success = ViewHierarchyAnimator.animate(
                rootView,
                animateChildren = false
        )
        // Change all bounds.
        rootView.measure(
                View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        rootView.layout(10 /* l */, 20 /* t */, 200 /* r */, 120 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_animator))
        assertNull(rootView.getChildAt(0).getTag(R.id.tag_animator))
        assertNull(rootView.getChildAt(1).getTag(R.id.tag_animator))
        // The initial values for the root view should be those of the previous layout, while the
        // children views should be at the final values from the beginning.
        checkBounds(rootView, l = 0, t = 0, r = 200, b = 100)
        checkBounds(rootView.getChildAt(0), l = 0, t = 0, r = 90, b = 100)
        checkBounds(rootView.getChildAt(1), l = 90, t = 0, r = 180, b = 100)
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        assertNull(rootView.getChildAt(0).getTag(R.id.tag_animator))
        assertNull(rootView.getChildAt(1).getTag(R.id.tag_animator))
        // The end values should be those of the latest layout.
        checkBounds(rootView, l = 10, t = 20, r = 200, b = 120)
        checkBounds(rootView.getChildAt(0), l = 0, t = 0, r = 90, b = 100)
        checkBounds(rootView.getChildAt(1), l = 90, t = 0, r = 180, b = 100)
    }

    @Test
    fun animatesInvisibleViews() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)
        rootView.visibility = View.INVISIBLE

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
    fun animatesAppearingViewsFadeIn_alphaStartsAtZero_endsAtOne() {
        rootView.alpha = 0f
        ViewHierarchyAnimator.animateAddition(rootView, includeFadeIn = true)
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        advanceFadeInAnimation(rootView, fraction = 1f)
        endFadeInAnimation(rootView)

        assertNull(rootView.getTag(R.id.tag_alpha_animator))
        assertEquals(1f, rootView.alpha)
    }

    @Test
    fun animatesAppearingViewsFadeIn_alphaStartsAboveZero_endsAtOne() {
        rootView.alpha = 0.2f
        ViewHierarchyAnimator.animateAddition(rootView, includeFadeIn = true)
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        advanceFadeInAnimation(rootView, fraction = 1f)
        endFadeInAnimation(rootView)

        assertNull(rootView.getTag(R.id.tag_alpha_animator))
        assertEquals(1f, rootView.alpha)
    }

    @Test
    fun animatesAppearingViewsFadeIn_alphaStartsAsZero_alphaUpdatedMidAnimation() {
        rootView.alpha = 0f
        ViewHierarchyAnimator.animateAddition(
            rootView,
            includeFadeIn = true,
            fadeInInterpolator = Interpolators.LINEAR
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        advanceFadeInAnimation(rootView, fraction = 0.42f)

        assertEquals(0.42f, rootView.alpha)
    }

    @Test
    fun animatesAppearingViewsFadeIn_alphaStartsAboveZero_alphaUpdatedMidAnimation() {
        rootView.alpha = 0.6f
        ViewHierarchyAnimator.animateAddition(
            rootView,
            includeFadeIn = true,
            fadeInInterpolator = Interpolators.LINEAR
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        advanceFadeInAnimation(rootView, fraction = 0.5f)

        assertEquals(0.8f, rootView.alpha)
    }

    @Test
    fun animatesAppearingViewsFadeIn_childViewAlphasAlsoAnimated() {
        rootView.alpha = 0f
        val firstChild = View(context)
        firstChild.alpha = 0f
        val secondChild = View(context)
        secondChild.alpha = 0f
        rootView.addView(firstChild)
        rootView.addView(secondChild)

        ViewHierarchyAnimator.animateAddition(
            rootView,
            includeFadeIn = true,
            fadeInInterpolator = Interpolators.LINEAR
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        advanceFadeInAnimation(rootView, fraction = 0.5f)

        assertEquals(0.5f, rootView.alpha)
        assertEquals(0.5f, firstChild.alpha)
        assertEquals(0.5f, secondChild.alpha)
    }

    @Test
    fun animatesAppearingViewsFadeIn_animatesFromPreviousAnimationProgress() {
        rootView.alpha = 0f
        ViewHierarchyAnimator.animateAddition(
            rootView,
            includeFadeIn = true,
            fadeInInterpolator = Interpolators.LINEAR
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        advanceFadeInAnimation(rootView, fraction = 0.5f)
        assertEquals(0.5f, rootView.alpha)
        assertNotNull(rootView.getTag(R.id.tag_alpha_animator))

        // IF we request animation again
        ViewHierarchyAnimator.animateAddition(
            rootView,
            includeFadeIn = true,
            fadeInInterpolator = Interpolators.LINEAR
        )

        // THEN the alpha remains at its current value (it doesn't get reset to 0)
        assertNotNull(rootView.getTag(R.id.tag_alpha_animator))
        assertEquals(0.5f, rootView.alpha)

        // IF we advance the new animation to the end
        advanceFadeInAnimation(rootView, fraction = 1f)
        endFadeInAnimation(rootView)

        // THEN we still end at the correct value
        assertNull(rootView.getTag(R.id.tag_alpha_animator))
        assertEquals(1f, rootView.alpha)
    }

    @Test
    fun animatesAppearingViews_fadeInFalse_alphasNotUpdated() {
        rootView.alpha = 0.3f
        val firstChild = View(context)
        firstChild.alpha = 0.4f
        val secondChild = View(context)
        secondChild.alpha = 0.5f
        rootView.addView(firstChild)
        rootView.addView(secondChild)

        ViewHierarchyAnimator.animateAddition(
            rootView,
            includeFadeIn = false,
            fadeInInterpolator = Interpolators.LINEAR
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        advanceFadeInAnimation(rootView, fraction = 1f)

        assertEquals(0.3f, rootView.alpha)
        assertEquals(0.4f, firstChild.alpha)
        assertEquals(0.5f, secondChild.alpha)
    }

    @Test
    fun animateAddition_runnableRunsWhenAnimationEnds() {
        var runnableRun = false
        val onAnimationEndRunnable = { runnableRun = true }

        ViewHierarchyAnimator.animateAddition(
                rootView,
                origin = ViewHierarchyAnimator.Hotspot.CENTER,
                includeMargins = true,
                onAnimationEnd = onAnimationEndRunnable
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        endAnimation(rootView)

        assertEquals(true, runnableRun)
    }

    @Test
    fun animateAddition_runnableDoesNotRunWhenAnimationCancelled() {
        var runnableRun = false
        val onAnimationEndRunnable = { runnableRun = true }

        ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.CENTER,
            includeMargins = true,
            onAnimationEnd = onAnimationEndRunnable
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        cancelAnimation(rootView)

        assertEquals(false, runnableRun)
    }

    @Test
    fun animationAddition_runnableDoesNotRunWhenOnlyPartwayThroughAnimation() {
        var runnableRun = false
        val onAnimationEndRunnable = { runnableRun = true }

        ViewHierarchyAnimator.animateAddition(
            rootView,
            origin = ViewHierarchyAnimator.Hotspot.CENTER,
            includeMargins = true,
            onAnimationEnd = onAnimationEndRunnable
        )
        rootView.layout(50 /* l */, 50 /* t */, 100 /* r */, 100 /* b */)

        advanceAnimation(rootView, 0.5f)

        assertEquals(false, runnableRun)
    }

    @Test
    fun animatesViewRemovalFromStartToEnd_viewHasSiblings() {
        setUpRootWithChildren()

        val child = rootView.getChildAt(0)
        val success = ViewHierarchyAnimator.animateRemoval(
            child,
            destination = ViewHierarchyAnimator.Hotspot.LEFT,
            interpolator = Interpolators.LINEAR
        )

        assertTrue(success)
        assertNotNull(child.getTag(R.id.tag_animator))
        checkBounds(child, l = 0, t = 0, r = 100, b = 100)
        advanceAnimation(child, 0.5f)
        checkBounds(child, l = 0, t = 0, r = 50, b = 100)
        advanceAnimation(child, 1.0f)
        checkBounds(child, l = 0, t = 0, r = 0, b = 100)
        endAnimation(rootView)
        endAnimation(child)
        assertEquals(1, rootView.childCount)
        assertFalse(child in rootView.children)
    }

    @Test
    fun animatesViewRemovalFromStartToEnd_viewHasNoSiblings() {
        rootView = LinearLayout(mContext)
        (rootView as LinearLayout).orientation = LinearLayout.HORIZONTAL
        (rootView as LinearLayout).weightSum = 1f

        val onlyChild = View(mContext)
        rootView.addView(onlyChild)
        forceLayout()

        val success = ViewHierarchyAnimator.animateRemoval(
            onlyChild,
            destination = ViewHierarchyAnimator.Hotspot.LEFT,
            interpolator = Interpolators.LINEAR
        )

        assertTrue(success)
        assertNotNull(onlyChild.getTag(R.id.tag_animator))
        checkBounds(onlyChild, l = 0, t = 0, r = 200, b = 100)
        advanceAnimation(onlyChild, 0.5f)
        checkBounds(onlyChild, l = 0, t = 0, r = 100, b = 100)
        advanceAnimation(onlyChild, 1.0f)
        checkBounds(onlyChild, l = 0, t = 0, r = 0, b = 100)
        endAnimation(rootView)
        endAnimation(onlyChild)
        assertEquals(0, rootView.childCount)
        assertFalse(onlyChild in rootView.children)
    }

    @Test
    fun animatesViewRemovalRespectingDestination() {
        // CENTER
        setUpRootWithChildren()
        var removedChild = rootView.getChildAt(0)
        var remainingChild = rootView.getChildAt(1)
        var success = ViewHierarchyAnimator.animateRemoval(
            removedChild, destination = ViewHierarchyAnimator.Hotspot.CENTER
        )
        // Ensure that the layout happens before the checks.
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(removedChild, l = 50, t = 50, r = 50, b = 50)
        endAnimation(rootView)
        endAnimation(removedChild)
        checkBounds(remainingChild, l = 0, t = 0, r = 100, b = 100)

        // LEFT
        setUpRootWithChildren()
        removedChild = rootView.getChildAt(0)
        remainingChild = rootView.getChildAt(1)
        success = ViewHierarchyAnimator.animateRemoval(
            removedChild, destination = ViewHierarchyAnimator.Hotspot.LEFT
        )
        // Ensure that the layout happens before the checks.
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(removedChild, l = 0, t = 0, r = 0, b = 100)
        endAnimation(rootView)
        endAnimation(removedChild)
        checkBounds(remainingChild, l = 0, t = 0, r = 100, b = 100)

        // TOP_LEFT
        setUpRootWithChildren()
        removedChild = rootView.getChildAt(0)
        remainingChild = rootView.getChildAt(1)
        success = ViewHierarchyAnimator.animateRemoval(
            removedChild, destination = ViewHierarchyAnimator.Hotspot.TOP_LEFT
        )
        // Ensure that the layout happens before the checks.
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(removedChild, l = 0, t = 0, r = 0, b = 0)
        endAnimation(rootView)
        endAnimation(removedChild)
        checkBounds(remainingChild, l = 0, t = 0, r = 100, b = 100)

        // TOP
        setUpRootWithChildren()
        removedChild = rootView.getChildAt(0)
        remainingChild = rootView.getChildAt(1)
        success = ViewHierarchyAnimator.animateRemoval(
            removedChild, destination = ViewHierarchyAnimator.Hotspot.TOP
        )
        // Ensure that the layout happens before the checks.
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(removedChild, l = 0, t = 0, r = 100, b = 0)
        endAnimation(rootView)
        endAnimation(removedChild)
        checkBounds(remainingChild, l = 0, t = 0, r = 100, b = 100)

        // TOP_RIGHT
        setUpRootWithChildren()
        removedChild = rootView.getChildAt(0)
        remainingChild = rootView.getChildAt(1)
        success = ViewHierarchyAnimator.animateRemoval(
            removedChild, destination = ViewHierarchyAnimator.Hotspot.TOP_RIGHT
        )
        // Ensure that the layout happens before the checks.
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(removedChild, l = 100, t = 0, r = 100, b = 0)
        endAnimation(rootView)
        endAnimation(removedChild)
        checkBounds(remainingChild, l = 0, t = 0, r = 100, b = 100)

        // RIGHT
        setUpRootWithChildren()
        removedChild = rootView.getChildAt(0)
        remainingChild = rootView.getChildAt(1)
        success = ViewHierarchyAnimator.animateRemoval(
            removedChild, destination = ViewHierarchyAnimator.Hotspot.RIGHT
        )
        // Ensure that the layout happens before the checks.
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(removedChild, l = 100, t = 0, r = 100, b = 100)
        endAnimation(rootView)
        endAnimation(removedChild)
        checkBounds(remainingChild, l = 0, t = 0, r = 100, b = 100)

        // BOTTOM_RIGHT
        setUpRootWithChildren()
        removedChild = rootView.getChildAt(0)
        remainingChild = rootView.getChildAt(1)
        success = ViewHierarchyAnimator.animateRemoval(
            removedChild, destination = ViewHierarchyAnimator.Hotspot.BOTTOM_RIGHT
        )
        // Ensure that the layout happens before the checks.
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(removedChild, l = 100, t = 100, r = 100, b = 100)
        endAnimation(rootView)
        endAnimation(removedChild)
        checkBounds(remainingChild, l = 0, t = 0, r = 100, b = 100)

        // BOTTOM
        setUpRootWithChildren()
        removedChild = rootView.getChildAt(0)
        remainingChild = rootView.getChildAt(1)
        success = ViewHierarchyAnimator.animateRemoval(
            removedChild, destination = ViewHierarchyAnimator.Hotspot.BOTTOM
        )
        // Ensure that the layout happens before the checks.
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(removedChild, l = 0, t = 100, r = 100, b = 100)
        endAnimation(rootView)
        endAnimation(removedChild)
        checkBounds(remainingChild, l = 0, t = 0, r = 100, b = 100)

        // BOTTOM_LEFT
        setUpRootWithChildren()
        removedChild = rootView.getChildAt(0)
        remainingChild = rootView.getChildAt(1)
        success = ViewHierarchyAnimator.animateRemoval(
            removedChild, destination = ViewHierarchyAnimator.Hotspot.BOTTOM_LEFT
        )
        // Ensure that the layout happens before the checks.
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(removedChild, l = 0, t = 100, r = 0, b = 100)
        endAnimation(rootView)
        endAnimation(removedChild)
        checkBounds(remainingChild, l = 0, t = 0, r = 100, b = 100)
    }

    /* ******** start of animatesViewRemoval_includeMarginsTrue tests ******** */
    @Test
    fun animatesViewRemoval_includeMarginsTrue_center() {
        setUpRootWithChildren(includeMarginsOnFirstChild = true)
        val removedChild = rootView.getChildAt(0)
        val originalLeft = removedChild.left
        val originalTop = removedChild.top
        val originalRight = removedChild.right
        val originalBottom = removedChild.bottom

        val success = ViewHierarchyAnimator.animateRemoval(
            removedChild,
            destination = ViewHierarchyAnimator.Hotspot.CENTER,
            includeMargins = true,
        )
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        val expectedX = ((originalLeft - M_LEFT) + (originalRight + M_RIGHT)) / 2
        val expectedY = ((originalTop - M_TOP) + (originalBottom + M_BOTTOM)) / 2

        checkBounds(
            removedChild,
            l = expectedX,
            t = expectedY,
            r = expectedX,
            b = expectedY
        )
    }

    @Test
    fun animatesViewRemoval_includeMarginsTrue_left() {
        setUpRootWithChildren(includeMarginsOnFirstChild = true)
        val removedChild = rootView.getChildAt(0)
        val originalLeft = removedChild.left
        val originalTop = removedChild.top
        val originalBottom = removedChild.bottom

        val success = ViewHierarchyAnimator.animateRemoval(
            removedChild,
            destination = ViewHierarchyAnimator.Hotspot.LEFT,
            includeMargins = true,
        )
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(
            removedChild,
            l = originalLeft - M_LEFT,
            t = originalTop,
            r = originalLeft - M_LEFT,
            b = originalBottom
        )
    }

    @Test
    fun animatesViewRemoval_includeMarginsTrue_topLeft() {
        setUpRootWithChildren(includeMarginsOnFirstChild = true)
        val removedChild = rootView.getChildAt(0)
        val originalLeft = removedChild.left
        val originalTop = removedChild.top

        val success = ViewHierarchyAnimator.animateRemoval(
            removedChild,
            destination = ViewHierarchyAnimator.Hotspot.TOP_LEFT,
            includeMargins = true,
        )
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(
            removedChild,
            l = originalLeft - M_LEFT,
            t = originalTop - M_TOP,
            r = originalLeft - M_LEFT,
            b = originalTop - M_TOP
        )
    }

    @Test
    fun animatesViewRemoval_includeMarginsTrue_top() {
        setUpRootWithChildren(includeMarginsOnFirstChild = true)
        val removedChild = rootView.getChildAt(0)
        val originalLeft = removedChild.left
        val originalTop = removedChild.top
        val originalRight = removedChild.right

        val success = ViewHierarchyAnimator.animateRemoval(
            removedChild,
            destination = ViewHierarchyAnimator.Hotspot.TOP,
            includeMargins = true,
        )
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(
            removedChild,
            l = originalLeft,
            t = originalTop - M_TOP,
            r = originalRight,
            b = originalTop - M_TOP
        )
    }

    @Test
    fun animatesViewRemoval_includeMarginsTrue_topRight() {
        setUpRootWithChildren(includeMarginsOnFirstChild = true)
        val removedChild = rootView.getChildAt(0)
        val originalTop = removedChild.top
        val originalRight = removedChild.right

        val success = ViewHierarchyAnimator.animateRemoval(
            removedChild,
            destination = ViewHierarchyAnimator.Hotspot.TOP_RIGHT,
            includeMargins = true,
        )
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(
            removedChild,
            l = originalRight + M_RIGHT,
            t = originalTop - M_TOP,
            r = originalRight + M_RIGHT,
            b = originalTop - M_TOP
        )
    }

    @Test
    fun animatesViewRemoval_includeMarginsTrue_right() {
        setUpRootWithChildren(includeMarginsOnFirstChild = true)
        val removedChild = rootView.getChildAt(0)
        val originalTop = removedChild.top
        val originalRight = removedChild.right
        val originalBottom = removedChild.bottom

        val success = ViewHierarchyAnimator.animateRemoval(
            removedChild,
            destination = ViewHierarchyAnimator.Hotspot.RIGHT,
            includeMargins = true,
        )
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(
            removedChild,
            l = originalRight + M_RIGHT,
            t = originalTop,
            r = originalRight + M_RIGHT,
            b = originalBottom
        )
    }

    @Test
    fun animatesViewRemoval_includeMarginsTrue_bottomRight() {
        setUpRootWithChildren(includeMarginsOnFirstChild = true)
        val removedChild = rootView.getChildAt(0)
        val originalRight = removedChild.right
        val originalBottom = removedChild.bottom

        val success = ViewHierarchyAnimator.animateRemoval(
            removedChild,
            destination = ViewHierarchyAnimator.Hotspot.BOTTOM_RIGHT,
            includeMargins = true,
        )
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(
            removedChild,
            l = originalRight + M_RIGHT,
            t = originalBottom + M_BOTTOM,
            r = originalRight + M_RIGHT,
            b = originalBottom + M_BOTTOM
        )
    }

    @Test
    fun animatesViewRemoval_includeMarginsTrue_bottom() {
        setUpRootWithChildren(includeMarginsOnFirstChild = true)
        val removedChild = rootView.getChildAt(0)
        val originalLeft = removedChild.left
        val originalRight = removedChild.right
        val originalBottom = removedChild.bottom

        val success = ViewHierarchyAnimator.animateRemoval(
            removedChild,
            destination = ViewHierarchyAnimator.Hotspot.BOTTOM,
            includeMargins = true,
        )
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(
            removedChild,
            l = originalLeft,
            t = originalBottom + M_BOTTOM,
            r = originalRight,
            b = originalBottom + M_BOTTOM
        )
    }

    @Test
    fun animatesViewRemoval_includeMarginsTrue_bottomLeft() {
        setUpRootWithChildren(includeMarginsOnFirstChild = true)
        val removedChild = rootView.getChildAt(0)
        val originalLeft = removedChild.left
        val originalBottom = removedChild.bottom

        val success = ViewHierarchyAnimator.animateRemoval(
            removedChild,
            destination = ViewHierarchyAnimator.Hotspot.BOTTOM_LEFT,
            includeMargins = true,
        )
        forceLayout()

        assertTrue(success)
        assertNotNull(removedChild.getTag(R.id.tag_animator))
        advanceAnimation(removedChild, 1.0f)
        checkBounds(
            removedChild,
            l = originalLeft - M_LEFT,
            t = originalBottom + M_BOTTOM,
            r = originalLeft - M_LEFT,
            b = originalBottom + M_BOTTOM
        )
    }
    /* ******** end of animatesViewRemoval_includeMarginsTrue tests ******** */

    @Test
    fun animatesChildrenDuringViewRemoval() {
        setUpRootWithChildren()

        val child = rootView.getChildAt(0) as ViewGroup
        val firstGrandChild = child.getChildAt(0)
        val secondGrandChild = child.getChildAt(1)
        val success = ViewHierarchyAnimator.animateRemoval(
            child, interpolator = Interpolators.LINEAR
        )

        assertTrue(success)
        assertNotNull(child.getTag(R.id.tag_animator))
        assertNotNull(firstGrandChild.getTag(R.id.tag_animator))
        assertNotNull(secondGrandChild.getTag(R.id.tag_animator))
        checkBounds(child, l = 0, t = 0, r = 100, b = 100)
        checkBounds(firstGrandChild, l = 0, t = 0, r = 40, b = 40)
        checkBounds(secondGrandChild, l = 60, t = 60, r = 100, b = 100)

        advanceAnimation(child, 0.5f)
        checkBounds(child, l = 25, t = 25, r = 75, b = 75)
        checkBounds(firstGrandChild, l = -10, t = -10, r = 30, b = 30)
        checkBounds(secondGrandChild, l = 20, t = 20, r = 60, b = 60)

        advanceAnimation(child, 1.0f)
        checkBounds(child, l = 50, t = 50, r = 50, b = 50)
        checkBounds(firstGrandChild, l = -20, t = -20, r = 20, b = 20)
        checkBounds(secondGrandChild, l = -20, t = -20, r = 20, b = 20)

        endAnimation(rootView)
        endAnimation(child)
    }

    @Test
    fun animatesSiblingsDuringViewRemoval() {
        setUpRootWithChildren()

        val removedChild = rootView.getChildAt(0)
        val remainingChild = rootView.getChildAt(1)
        val success = ViewHierarchyAnimator.animateRemoval(
            removedChild, interpolator = Interpolators.LINEAR
        )
        // Ensure that the layout happens before the checks.
        forceLayout()

        assertTrue(success)
        assertNotNull(remainingChild.getTag(R.id.tag_animator))
        checkBounds(remainingChild, l = 100, t = 0, r = 200, b = 100)
        advanceAnimation(rootView, 0.5f)
        checkBounds(remainingChild, l = 50, t = 0, r = 150, b = 100)
        advanceAnimation(rootView, 1.0f)
        checkBounds(remainingChild, l = 0, t = 0, r = 100, b = 100)
        endAnimation(rootView)
        endAnimation(removedChild)
        assertNull(remainingChild.getTag(R.id.tag_animator))
    }

    @Test
    fun animateRemoval_runnableRunsWhenAnimationEnds() {
        var runnableRun = false
        val onAnimationEndRunnable = { runnableRun = true }

        setUpRootWithChildren()
        forceLayout()
        val removedView = rootView.getChildAt(0)

        ViewHierarchyAnimator.animateRemoval(
            removedView,
            onAnimationEnd = onAnimationEndRunnable
        )
        endAnimation(removedView)

        assertEquals(true, runnableRun)
    }

    @Test
    fun animateRemoval_runnableDoesNotRunWhenAnimationCancelled() {
        var runnableRun = false
        val onAnimationEndRunnable = { runnableRun = true }

        setUpRootWithChildren()
        forceLayout()
        val removedView = rootView.getChildAt(0)

        ViewHierarchyAnimator.animateRemoval(
            removedView,
            onAnimationEnd = onAnimationEndRunnable
        )
        cancelAnimation(removedView)

        assertEquals(false, runnableRun)
    }

    @Test
    fun animationRemoval_runnableDoesNotRunWhenOnlyPartwayThroughAnimation() {
        var runnableRun = false
        val onAnimationEndRunnable = { runnableRun = true }

        setUpRootWithChildren()
        forceLayout()
        val removedView = rootView.getChildAt(0)

        ViewHierarchyAnimator.animateRemoval(
            removedView,
            onAnimationEnd = onAnimationEndRunnable
        )
        advanceAnimation(removedView, 0.5f)

        assertEquals(false, runnableRun)
    }

    @Test
    fun cleansUpListenersCorrectly() {
        val firstChild = View(mContext)
        firstChild.layoutParams = LinearLayout.LayoutParams(50 /* width */, 100 /* height */)
        rootView.addView(firstChild)
        val secondChild = View(mContext)
        secondChild.layoutParams = LinearLayout.LayoutParams(50 /* width */, 100 /* height */)
        rootView.addView(secondChild)
        rootView.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        rootView.layout(0 /* l */, 0 /* t */, 100 /* r */, 100 /* b */)

        val success = ViewHierarchyAnimator.animateNextUpdate(rootView)
        // Change all bounds.
        rootView.measure(
            View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        rootView.layout(0 /* l */, 0 /* t */, 150 /* r */, 100 /* b */)

        assertTrue(success)
        assertNotNull(rootView.getTag(R.id.tag_layout_listener))
        assertNotNull(firstChild.getTag(R.id.tag_layout_listener))
        assertNotNull(secondChild.getTag(R.id.tag_layout_listener))
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_layout_listener))
        assertNull(firstChild.getTag(R.id.tag_layout_listener))
        assertNull(secondChild.getTag(R.id.tag_layout_listener))
    }

    @Test
    fun doesNotAnimateGoneViews() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        // GONE
        rootView.visibility = View.GONE
        var success = ViewHierarchyAnimator.animate(rootView)
        rootView.layout(0 /* l */, 15 /* t */, 55 /* r */, 80 /* b */)

        assertFalse(success)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 15, r = 55, b = 80)
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

    private fun setUpRootWithChildren(includeMarginsOnFirstChild: Boolean = false) {
        rootView = LinearLayout(mContext)
        (rootView as LinearLayout).orientation = LinearLayout.HORIZONTAL
        (rootView as LinearLayout).weightSum = 1f

        val firstChild = RelativeLayout(mContext)
        rootView.addView(firstChild)
        val firstGrandChild = View(mContext)
        firstChild.addView(firstGrandChild)
        val secondGrandChild = View(mContext)
        firstChild.addView(secondGrandChild)
        val secondChild = View(mContext)
        rootView.addView(secondChild)

        val firstChildParams = LinearLayout.LayoutParams(
            0 /* width */,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        firstChildParams.weight = 0.5f
        if (includeMarginsOnFirstChild) {
            firstChildParams.leftMargin = M_LEFT
            firstChildParams.topMargin = M_TOP
            firstChildParams.rightMargin = M_RIGHT
            firstChildParams.bottomMargin = M_BOTTOM
        }
        firstChild.layoutParams = firstChildParams

        val secondChildParams = LinearLayout.LayoutParams(
            0 /* width */,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        secondChildParams.weight = 0.5f
        secondChild.layoutParams = secondChildParams

        firstGrandChild.layoutParams = RelativeLayout.LayoutParams(40 /* width */, 40 /* height */)
        (firstGrandChild.layoutParams as RelativeLayout.LayoutParams)
            .addRule(RelativeLayout.ALIGN_PARENT_START)
        (firstGrandChild.layoutParams as RelativeLayout.LayoutParams)
            .addRule(RelativeLayout.ALIGN_PARENT_TOP)
        secondGrandChild.layoutParams = RelativeLayout.LayoutParams(40 /* width */, 40 /* height */)
        (secondGrandChild.layoutParams as RelativeLayout.LayoutParams)
            .addRule(RelativeLayout.ALIGN_PARENT_END)
        (secondGrandChild.layoutParams as RelativeLayout.LayoutParams)
            .addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)

        forceLayout()
    }

    private fun forceLayout() {
        rootView.measure(
            View.MeasureSpec.makeMeasureSpec(200 /* width */, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(100 /* height */, View.MeasureSpec.AT_MOST)
        )
        rootView.layout(0 /* l */, 0 /* t */, 200 /* r */, 100 /* b */)
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

    private fun advanceFadeInAnimation(rootView: View, fraction: Float) {
        (rootView.getTag(R.id.tag_alpha_animator) as? ObjectAnimator)?.setCurrentFraction(fraction)

        if (rootView is ViewGroup) {
            for (i in 0 until rootView.childCount) {
                advanceFadeInAnimation(rootView.getChildAt(i), fraction)
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

    private fun cancelAnimation(rootView: View) {
        (rootView.getTag(R.id.tag_animator) as? ObjectAnimator)?.cancel()

        if (rootView is ViewGroup) {
            for (i in 0 until rootView.childCount) {
                cancelAnimation(rootView.getChildAt(i))
            }
        }
    }

    private fun endFadeInAnimation(rootView: View) {
        (rootView.getTag(R.id.tag_alpha_animator) as? ObjectAnimator)?.end()

        if (rootView is ViewGroup) {
            for (i in 0 until rootView.childCount) {
                endFadeInAnimation(rootView.getChildAt(i))
            }
        }
    }
}

// Margin values.
private const val M_LEFT = 14
private const val M_TOP = 16
private const val M_RIGHT = 18
private const val M_BOTTOM = 20

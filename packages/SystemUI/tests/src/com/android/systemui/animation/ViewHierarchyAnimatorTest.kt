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
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ViewHierarchyAnimatorTest : SysuiTestCase() {
    companion object {
        private const val TEST_DURATION = 1000L
        private val TEST_INTERPOLATOR = Interpolators.LINEAR
    }

    private lateinit var rootView: LinearLayout

    @Before
    fun setUp() {
        rootView = LinearLayout(mContext)
    }

    @After
    fun tearDown() {
        ViewHierarchyAnimator.stopAnimating(rootView)
    }

    @Test
    fun respectsAnimationParameters() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        ViewHierarchyAnimator.animate(
            rootView, interpolator = TEST_INTERPOLATOR, duration = TEST_DURATION
        )
        rootView.layout(0 /* l */, 0 /* t */, 100 /* r */, 100 /* b */)

        assertNotNull(rootView.getTag(R.id.tag_animator))
        val animator = rootView.getTag(R.id.tag_animator) as ObjectAnimator
        assertEquals(animator.interpolator, TEST_INTERPOLATOR)
        assertEquals(animator.duration, TEST_DURATION)
    }

    @Test
    fun animatesFromStartToEnd() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        ViewHierarchyAnimator.animate(rootView)
        // Change all bounds.
        rootView.layout(0 /* l */, 15 /* t */, 70 /* r */, 80 /* b */)

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

        ViewHierarchyAnimator.animate(rootView)
        // Change all bounds.
        rootView.layout(0 /* l */, 15 /* t */, 70 /* r */, 80 /* b */)

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

        ViewHierarchyAnimator.animateNextUpdate(rootView, interpolator = TEST_INTERPOLATOR)
        // Change all bounds.
        rootView.layout(0 /* l */, 20 /* t */, 70 /* r */, 80 /* b */)

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
        rootView.addView(firstChild)
        val secondChild = View(mContext)
        rootView.addView(secondChild)
        rootView.layout(0 /* l */, 0 /* t */, 150 /* r */, 100 /* b */)
        firstChild.layout(0 /* l */, 0 /* t */, 100 /* r */, 100 /* b */)
        secondChild.layout(100 /* l */, 0 /* t */, 150 /* r */, 100 /* b */)

        ViewHierarchyAnimator.animate(rootView)
        // Change all bounds.
        rootView.layout(10 /* l */, 20 /* t */, 200 /* r */, 120 /* b */)
        firstChild.layout(10 /* l */, 20 /* t */, 150 /* r */, 120 /* b */)
        secondChild.layout(150 /* l */, 20 /* t */, 200 /* r */, 120 /* b */)

        assertNotNull(rootView.getTag(R.id.tag_animator))
        // The initial values should be those of the previous layout.
        checkBounds(rootView, l = 0, t = 0, r = 150, b = 100)
        checkBounds(firstChild, l = 0, t = 0, r = 100, b = 100)
        checkBounds(secondChild, l = 100, t = 0, r = 150, b = 100)
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        // The end values should be those of the latest layout.
        checkBounds(rootView, l = 10, t = 20, r = 200, b = 120)
        checkBounds(firstChild, l = 10, t = 20, r = 150, b = 120)
        checkBounds(secondChild, l = 150, t = 20, r = 200, b = 120)
    }

    @Test
    fun doesNotAnimateInvisibleViews() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        ViewHierarchyAnimator.animate(rootView)
        // GONE.
        rootView.visibility = View.GONE
        rootView.layout(0 /* l */, 15 /* t */, 55 /* r */, 80 /* b */)

        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 15, r = 55, b = 80)

        // INVISIBLE.
        rootView.visibility = View.INVISIBLE
        rootView.layout(0 /* l */, 20 /* t */, 0 /* r */, 20 /* b */)
    }

    @Test
    fun doesNotAnimateUnchangingBounds() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        ViewHierarchyAnimator.animate(rootView)
        // No bounds are changed.
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

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
    fun doesNotAnimateExcludedBounds() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        ViewHierarchyAnimator.animate(
            rootView,
            bounds = setOf(ViewHierarchyAnimator.Bound.LEFT, ViewHierarchyAnimator.Bound.TOP),
            interpolator = TEST_INTERPOLATOR
        )
        // Change all bounds.
        rootView.layout(0 /* l */, 20 /* t */, 70 /* r */, 80 /* b */)

        assertNotNull(rootView.getTag(R.id.tag_animator))
        advanceAnimation(rootView, 0.5f)
        checkBounds(rootView, l = 5, t = 15, r = 70, b = 80)
        endAnimation(rootView)
        assertNull(rootView.getTag(R.id.tag_animator))
        checkBounds(rootView, l = 0, t = 20, r = 70, b = 80)
    }

    @Test
    fun stopsAnimatingAfterSingleLayout() {
        rootView.layout(10 /* l */, 10 /* t */, 50 /* r */, 50 /* b */)

        ViewHierarchyAnimator.animateNextUpdate(rootView)
        // Change all bounds.
        rootView.layout(0 /* l */, 15 /* t */, 70 /* r */, 80 /* b */)

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

        ViewHierarchyAnimator.animate(rootView)
        // Change all bounds.
        rootView.layout(0 /* l */, 15 /* t */, 70 /* r */, 80 /* b */)

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

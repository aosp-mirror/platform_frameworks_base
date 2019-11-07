package com.android.systemui.util.animation

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.util.ArrayMap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringForce
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.animation.PhysicsAnimator.EndListener
import com.android.systemui.util.animation.PhysicsAnimator.UpdateListener
import com.android.systemui.util.animation.PhysicsAnimatorTestUtils.clearAnimationUpdateFrames
import com.android.systemui.util.animation.PhysicsAnimatorTestUtils.getAnimationUpdateFrames
import com.android.systemui.util.animation.PhysicsAnimatorTestUtils.verifyAnimationUpdateFrames
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner::class)
@SmallTest
class PhysicsAnimatorTest : SysuiTestCase() {
    private lateinit var viewGroup: ViewGroup
    private lateinit var testView: View
    private lateinit var testView2: View

    private lateinit var animator: PhysicsAnimator<View>

    private val springConfig = PhysicsAnimator.SpringConfig(
            SpringForce.STIFFNESS_LOW, SpringForce.DAMPING_RATIO_NO_BOUNCY)
    private val flingConfig = PhysicsAnimator.FlingConfig(2f)

    private lateinit var mockUpdateListener: UpdateListener<View>
    private lateinit var mockEndListener: EndListener<View>
    private lateinit var mockEndAction: Runnable

    private fun <T> eq(value: T): T = Mockito.eq(value) ?: value

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        mockUpdateListener = mock(UpdateListener::class.java) as UpdateListener<View>
        mockEndListener = mock(EndListener::class.java) as EndListener<View>
        mockEndAction = mock(Runnable::class.java)

        viewGroup = FrameLayout(context)
        testView = View(context)
        testView2 = View(context)
        viewGroup.addView(testView)
        viewGroup.addView(testView2)

        PhysicsAnimatorTestUtils.prepareForTest()

        // Most of our tests involve checking the end state of animations, so we want calls that
        // start animations to block the test thread until the animations have ended.
        PhysicsAnimatorTestUtils.setAllAnimationsBlock(true)

        animator = PhysicsAnimator.getInstance(testView)
    }

    @After
    fun tearDown() {
        PhysicsAnimatorTestUtils.tearDown()
    }

    @Test
    fun testOneAnimatorPerView() {
        assertEquals(animator, PhysicsAnimator.getInstance(testView))
        assertEquals(PhysicsAnimator.getInstance(testView), PhysicsAnimator.getInstance(testView))
        assertNotEquals(animator, PhysicsAnimator.getInstance(testView2))
    }

    @Test
    fun testSpringOneProperty() {
        animator
                .spring(DynamicAnimation.TRANSLATION_X, 50f, springConfig)
                .start()

        assertEquals(testView.translationX, 50f, 1f)
    }

    @Test
    fun testSpringMultipleProperties() {
        animator
                .spring(DynamicAnimation.TRANSLATION_X, 10f, springConfig)
                .spring(DynamicAnimation.TRANSLATION_Y, 50f, springConfig)
                .spring(DynamicAnimation.SCALE_Y, 1.1f, springConfig)
                .start()

        assertEquals(10f, testView.translationX, 1f)
        assertEquals(50f, testView.translationY, 1f)
        assertEquals(1.1f, testView.scaleY, 0.01f)
    }

    @Test
    fun testFling() {
        val startTime = System.currentTimeMillis()

        animator
                .fling(DynamicAnimation.TRANSLATION_X, 1000f /* startVelocity */, flingConfig)
                .fling(DynamicAnimation.TRANSLATION_Y, 500f, flingConfig)
                .start()

        val elapsedTimeSeconds = (System.currentTimeMillis() - startTime) / 1000f

        // If the fling worked, the view should be somewhere between its starting position and the
        // and the theoretical no-friction maximum of startVelocity (in pixels per second)
        // multiplied by elapsedTimeSeconds. We can't calculate an exact expected location for a
        // fling, so this is close enough.
        assertTrue(testView.translationX > 0f)
        assertTrue(testView.translationX < 1000f * elapsedTimeSeconds)
        assertTrue(testView.translationY > 0f)
        assertTrue(testView.translationY < 500f * elapsedTimeSeconds)
    }

    @Test
    @Throws(InterruptedException::class)
    fun testEndListenersAndActions() {
        PhysicsAnimatorTestUtils.setAllAnimationsBlock(false)
        animator
                .spring(DynamicAnimation.TRANSLATION_X, 10f, springConfig)
                .spring(DynamicAnimation.TRANSLATION_Y, 500f, springConfig)
                .addEndListener(mockEndListener)
                .withEndActions(mockEndAction::run)
                .start()

        PhysicsAnimatorTestUtils.blockUntilAnimationsEnd(animator, DynamicAnimation.TRANSLATION_X)

        // Once TRANSLATION_X is done, the view should be at x = 10...
        assertEquals(10f, testView.translationX, 1f)

        // / ...TRANSLATION_Y should still be running...
        assertTrue(animator.isPropertyAnimating(DynamicAnimation.TRANSLATION_Y))

        // ...and our end listener should have been called with x = 10, velocity = 0, and allEnded =
        // false since TRANSLATION_Y is still running.
        verify(mockEndListener).onAnimationEnd(
                testView,
                DynamicAnimation.TRANSLATION_X,
                canceled = false,
                finalValue = 10f,
                finalVelocity = 0f,
                allRelevantPropertyAnimsEnded = false)
        verifyNoMoreInteractions(mockEndListener)

        // The end action should not have been run yet.
        verify(mockEndAction, times(0)).run()

        // Block until TRANSLATION_Y finishes.
        PhysicsAnimatorTestUtils.blockUntilAnimationsEnd(animator, DynamicAnimation.TRANSLATION_Y)

        // The view should have been moved.
        assertEquals(10f, testView.translationX, 1f)
        assertEquals(500f, testView.translationY, 1f)

        // The end listener should have been called, this time with TRANSLATION_Y, y = 50, and
        // allEnded = true.
        verify(mockEndListener).onAnimationEnd(
                testView,
                DynamicAnimation.TRANSLATION_Y,
                canceled = false,
                finalValue = 500f,
                finalVelocity = 0f,
                allRelevantPropertyAnimsEnded = true)
        verifyNoMoreInteractions(mockEndListener)

        // Now that all properties are done animating, the end action should have been called.
        verify(mockEndAction, times(1)).run()
    }

    @Test
    fun testUpdateListeners() {
        animator
                .spring(DynamicAnimation.TRANSLATION_X, 100f, springConfig)
                .spring(DynamicAnimation.TRANSLATION_Y, 50f, springConfig)
                .addUpdateListener(object : UpdateListener<View> {
                    override fun onAnimationUpdateForProperty(
                        target: View,
                        values: UpdateMap<View>
                    ) {
                        mockUpdateListener.onAnimationUpdateForProperty(target, values)
                    }
                })
                .start()

        verifyUpdateListenerCalls(animator, mockUpdateListener)
    }

    @Test
    fun testListenersNotCalledOnSubsequentAnimations() {
        animator
                .spring(DynamicAnimation.TRANSLATION_X, 10f, springConfig)
                .addUpdateListener(mockUpdateListener)
                .addEndListener(mockEndListener)
                .withEndActions(mockEndAction::run)
                .start()

        verifyUpdateListenerCalls(animator, mockUpdateListener)
        verify(mockEndListener, times(1)).onAnimationEnd(
                eq(testView), eq(DynamicAnimation.TRANSLATION_X), eq(false), anyFloat(), anyFloat(),
                eq(true))
        verify(mockEndAction, times(1)).run()

        animator
                .spring(DynamicAnimation.TRANSLATION_X, 0f, springConfig)
                .start()

        // We didn't pass any of the listeners/actions to the subsequent animation, so they should
        // never have been called.
        verifyNoMoreInteractions(mockUpdateListener)
        verifyNoMoreInteractions(mockEndListener)
        verifyNoMoreInteractions(mockEndAction)
    }

    @Test
    @Throws(InterruptedException::class)
    fun testAnimationsUpdatedWhileInMotion() {
        PhysicsAnimatorTestUtils.setAllAnimationsBlock(false)

        // Spring towards x = 100f.
        animator
                .spring(
                        DynamicAnimation.TRANSLATION_X,
                        100f,
                        springConfig)
                .start()

        // Block until it reaches x = 50f.
        PhysicsAnimatorTestUtils.blockUntilFirstAnimationFrameWhereTrue(
                animator) { view -> view.translationX > 50f }

        // Translation X value at the time of reversing the animation to spring to x = 0f.
        val reversalTranslationX = testView.translationX

        // Spring back towards 0f.
        animator
                .spring(
                        DynamicAnimation.TRANSLATION_X,
                        0f,
                        // Lower the stiffness to ensure the update listener receives at least one
                        // update frame where the view has continued to move to the right.
                        springConfig.apply { stiffness = SpringForce.STIFFNESS_LOW })
                .start()

        // Wait for TRANSLATION_X.
        PhysicsAnimatorTestUtils.blockUntilAnimationsEnd(animator, DynamicAnimation.TRANSLATION_X)

        // Verify that the animation continued past the X value at the time of reversal, before
        // springing back. This ensures the change in direction was not abrupt.
        verifyAnimationUpdateFrames(
                animator, DynamicAnimation.TRANSLATION_X,
                { u -> u.value > reversalTranslationX },
                { u -> u.value < reversalTranslationX })

        // Verify that the view is where it should be.
        assertEquals(0f, testView.translationX, 1f)
    }

    @Test
    @Throws(InterruptedException::class)
    fun testAnimationsUpdatedWhileInMotion_originalListenersStillCalled() {
        PhysicsAnimatorTestUtils.setAllAnimationsBlock(false)

        // Spring TRANSLATION_X to 100f, with an update and end listener provided.
        animator
                .spring(DynamicAnimation.TRANSLATION_X, 100f, springConfig)
                .addUpdateListener(mockUpdateListener)
                .addEndListener(mockEndListener)
                .start()

        // Wait until the animation is halfway there.
        PhysicsAnimatorTestUtils.blockUntilFirstAnimationFrameWhereTrue(
                animator) { view -> view.translationX > 50f }

        // The end listener shouldn't have been called since the animation hasn't ended.
        verifyNoMoreInteractions(mockEndListener)

        // Make sure we called the update listener with appropriate values.
        verifyAnimationUpdateFrames(animator, DynamicAnimation.TRANSLATION_X,
                { u -> u.value > 0f },
                { u -> u.value >= 50f })

        // Mock a second end listener.
        val secondEndListener = mock(EndListener::class.java) as EndListener<View>
        val secondUpdateListener = mock(UpdateListener::class.java) as UpdateListener<View>

        // Start a new animation that springs both TRANSLATION_X and TRANSLATION_Y, and provide it
        // the second end listener. This new end listener should be called for the end of
        // TRANSLATION_X and TRANSLATION_Y, with allEnded = true when both have ended.
        animator
                .spring(DynamicAnimation.TRANSLATION_X, 200f, springConfig)
                .spring(DynamicAnimation.TRANSLATION_Y, 4000f, springConfig)
                .addUpdateListener(secondUpdateListener)
                .addEndListener(secondEndListener)
                .start()

        // Wait for TRANSLATION_X to end.
        PhysicsAnimatorTestUtils.blockUntilAnimationsEnd(animator, DynamicAnimation.TRANSLATION_X)

        // The update listener provided to the initial animation call (the one that only animated
        // TRANSLATION_X) should have been called with values on the way to x = 200f. This is
        // because the second animation call updated the original TRANSLATION_X animation.
        verifyAnimationUpdateFrames(
                animator, DynamicAnimation.TRANSLATION_X,
                { u -> u.value > 100f }, { u -> u.value >= 200f })

        // The original end listener should also have been called, with allEnded = true since it was
        // provided to an animator that animated only TRANSLATION_X.
        verify(mockEndListener, times(1))
                .onAnimationEnd(testView, DynamicAnimation.TRANSLATION_X, false, 200f, 0f, true)
        verifyNoMoreInteractions(mockEndListener)

        // The second end listener should have been called, but with allEnded = false since it was
        // provided to an animator that animated both TRANSLATION_X and TRANSLATION_Y.
        verify(secondEndListener, times(1))
                .onAnimationEnd(testView, DynamicAnimation.TRANSLATION_X, false, 200f, 0f, false)
        verifyNoMoreInteractions(secondEndListener)

        PhysicsAnimatorTestUtils.blockUntilAnimationsEnd(animator, DynamicAnimation.TRANSLATION_Y)

        // The original end listener shouldn't receive any callbacks because it was not provided to
        // an animator that animated TRANSLATION_Y.
        verifyNoMoreInteractions(mockEndListener)

        verify(secondEndListener, times(1))
                .onAnimationEnd(testView, DynamicAnimation.TRANSLATION_Y, false, 4000f, 0f, true)
        verifyNoMoreInteractions(secondEndListener)
    }

    @Test
    fun testFlingRespectsMinMax() {
        animator
                .fling(DynamicAnimation.TRANSLATION_X,
                        startVelocity = 1000f,
                        friction = 1.1f,
                        max = 10f)
                .addEndListener(mockEndListener)
                .start()

        // Ensure that the view stopped at x = 10f, and the end listener was called once with that
        // value.
        assertEquals(10f, testView.translationX, 1f)
        verify(mockEndListener, times(1))
                .onAnimationEnd(
                        eq(testView), eq(DynamicAnimation.TRANSLATION_X), eq(false), eq(10f),
                        anyFloat(), eq(true))

        animator
                .fling(
                        DynamicAnimation.TRANSLATION_X,
                        startVelocity = -1000f,
                        friction = 1.1f,
                        min = -5f)
                .addEndListener(mockEndListener)
                .start()

        // Ensure that the view stopped at x = -5f, and the end listener was called once with that
        // value.
        assertEquals(-5f, testView.translationX, 1f)
        verify(mockEndListener, times(1))
                .onAnimationEnd(
                        eq(testView), eq(DynamicAnimation.TRANSLATION_X), eq(false), eq(-5f),
                        anyFloat(), eq(true))
    }

    @Test
    fun testExtensionProperty() {
        testView
                .physicsAnimator
                .spring(DynamicAnimation.TRANSLATION_X, 200f)
                .start()

        assertEquals(200f, testView.translationX, 1f)
    }

    /**
     * Verifies that the calls to the mock update listener match the animation update frames
     * reported by the test internal listener, in order.
     */
    private fun <T : Any> verifyUpdateListenerCalls(
        animator: PhysicsAnimator<T>,
        mockUpdateListener: UpdateListener<T>
    ) {
        val updates = getAnimationUpdateFrames(animator)

        for (invocation in Mockito.mockingDetails(mockUpdateListener).invocations) {

            // Grab the update map of Property -> AnimationUpdate that was passed to the mock update
            // listener.
            val updateMap = invocation.arguments[1]
                    as ArrayMap<FloatPropertyCompat<in T>, PhysicsAnimator.AnimationUpdate>

            //
            for ((property, update) in updateMap) {
                val updatesForProperty = updates[property]!!

                // This update should be the next one in the list for this property.
                if (update != updatesForProperty[0]) {
                    Assert.fail("The update listener was called with an unexpected value: $update.")
                }

                updatesForProperty.remove(update)
            }

            // Mark this invocation verified.
            verify(mockUpdateListener).onAnimationUpdateForProperty(animator.target, updateMap)
        }

        verifyNoMoreInteractions(mockUpdateListener)

        // Since we were removing values as matching invocations were found, there should no longer
        // be any values remaining. If there are, it means the update listener wasn't notified when
        // it should have been.
        assertEquals(0,
                updates.values.fold(0, { count, propertyUpdates -> count + propertyUpdates.size }))

        clearAnimationUpdateFrames(animator)
    }
}
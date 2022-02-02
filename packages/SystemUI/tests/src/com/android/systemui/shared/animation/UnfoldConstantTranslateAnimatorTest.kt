package com.android.systemui.shared.animation

import android.testing.AndroidTestingRunner
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.Direction
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.ViewIdToTranslate
import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class UnfoldConstantTranslateAnimatorTest : SysuiTestCase() {

    @Mock private lateinit var progressProvider: UnfoldTransitionProgressProvider

    @Mock private lateinit var parent: ViewGroup

    @Captor private lateinit var progressListenerCaptor: ArgumentCaptor<TransitionProgressListener>

    private lateinit var animator: UnfoldConstantTranslateAnimator
    private lateinit var progressListener: TransitionProgressListener

    private val viewsIdToRegister =
        setOf(
            ViewIdToTranslate(LEFT_VIEW_ID, Direction.LEFT),
            ViewIdToTranslate(RIGHT_VIEW_ID, Direction.RIGHT))

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        animator =
            UnfoldConstantTranslateAnimator(viewsIdToRegister, progressProvider)

        animator.init(parent, MAX_TRANSLATION)

        verify(progressProvider).addCallback(progressListenerCaptor.capture())
        progressListener = progressListenerCaptor.value
    }

    @Test
    fun onTransition_noMatchingIds() {
        // GIVEN no views matching any ids
        // WHEN the transition starts
        progressListener.onTransitionStarted()
        progressListener.onTransitionProgress(.1f)

        // THEN nothing... no exceptions
    }

    @Test
    fun onTransition_oneMovesLeft() {
        // GIVEN one view with a matching id
        val view = View(context)
        whenever(parent.findViewById<View>(LEFT_VIEW_ID)).thenReturn(view)

        moveAndValidate(listOf(view to LEFT))
    }

    @Test
    fun onTransition_oneMovesLeftAndOneMovesRightMultipleTimes() {
        // GIVEN two views with a matching id
        val leftView = View(context)
        val rightView = View(context)
        whenever(parent.findViewById<View>(LEFT_VIEW_ID)).thenReturn(leftView)
        whenever(parent.findViewById<View>(RIGHT_VIEW_ID)).thenReturn(rightView)

        moveAndValidate(listOf(leftView to LEFT, rightView to RIGHT))
        moveAndValidate(listOf(leftView to LEFT, rightView to RIGHT))
    }

    private fun moveAndValidate(list: List<Pair<View, Int>>) {
        // Compare values as ints because -0f != 0f

        // WHEN the transition starts
        progressListener.onTransitionStarted()
        progressListener.onTransitionProgress(0f)

        list.forEach { (view, direction) ->
            assertEquals((-MAX_TRANSLATION * direction).toInt(), view.translationX.toInt())
        }

        // WHEN the transition progresses, translation is updated
        progressListener.onTransitionProgress(.5f)
        list.forEach { (view, direction) ->
            assertEquals((-MAX_TRANSLATION / 2f * direction).toInt(), view.translationX.toInt())
        }

        // WHEN the transition ends, translation is completed
        progressListener.onTransitionProgress(1f)
        progressListener.onTransitionFinished()
        list.forEach { (view, _) -> assertEquals(0, view.translationX.toInt()) }
    }

    companion object {
        private val LEFT = Direction.LEFT.multiplier.toInt()
        private val RIGHT = Direction.RIGHT.multiplier.toInt()

        private const val MAX_TRANSLATION = 42f

        private const val LEFT_VIEW_ID = 1
        private const val RIGHT_VIEW_ID = 2
    }
}

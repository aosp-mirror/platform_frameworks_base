package com.android.systemui.dreams

import android.animation.Animator
import android.animation.AnimatorSet
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dreams.complication.ComplicationHostViewController
import com.android.systemui.statusbar.BlurUtils
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class DreamOverlayAnimationsControllerTest : SysuiTestCase() {

    companion object {
        private const val DREAM_BLUR_RADIUS = 50
        private const val DREAM_IN_BLUR_ANIMATION_DURATION = 1L
        private const val DREAM_IN_COMPLICATIONS_ANIMATION_DURATION = 3L
        private const val DREAM_IN_TRANSLATION_Y_DISTANCE = 6
        private const val DREAM_IN_TRANSLATION_Y_DURATION = 7L
        private const val DREAM_OUT_TRANSLATION_Y_DISTANCE = 6
        private const val DREAM_OUT_TRANSLATION_Y_DURATION = 7L
        private const val DREAM_OUT_TRANSLATION_Y_DELAY_BOTTOM = 8L
        private const val DREAM_OUT_TRANSLATION_Y_DELAY_TOP = 9L
        private const val DREAM_OUT_ALPHA_DURATION = 10L
        private const val DREAM_OUT_ALPHA_DELAY_BOTTOM = 11L
        private const val DREAM_OUT_ALPHA_DELAY_TOP = 12L
        private const val DREAM_OUT_BLUR_DURATION = 13L
    }

    @Mock private lateinit var mockAnimator: AnimatorSet
    @Mock private lateinit var blurUtils: BlurUtils
    @Mock private lateinit var hostViewController: ComplicationHostViewController
    @Mock private lateinit var statusBarViewController: DreamOverlayStatusBarViewController
    @Mock private lateinit var stateController: DreamOverlayStateController
    private lateinit var controller: DreamOverlayAnimationsController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        controller =
            DreamOverlayAnimationsController(
                blurUtils,
                hostViewController,
                statusBarViewController,
                stateController,
                DREAM_BLUR_RADIUS,
                DREAM_IN_BLUR_ANIMATION_DURATION,
                DREAM_IN_COMPLICATIONS_ANIMATION_DURATION,
                DREAM_IN_TRANSLATION_Y_DISTANCE,
                DREAM_IN_TRANSLATION_Y_DURATION,
                DREAM_OUT_TRANSLATION_Y_DISTANCE,
                DREAM_OUT_TRANSLATION_Y_DURATION,
                DREAM_OUT_TRANSLATION_Y_DELAY_BOTTOM,
                DREAM_OUT_TRANSLATION_Y_DELAY_TOP,
                DREAM_OUT_ALPHA_DURATION,
                DREAM_OUT_ALPHA_DELAY_BOTTOM,
                DREAM_OUT_ALPHA_DELAY_TOP,
                DREAM_OUT_BLUR_DURATION
            )
    }

    @Test
    fun testExitAnimationOnEnd() {
        val mockCallback: () -> Unit = mock()

        controller.startExitAnimations(
            view = mock(),
            doneCallback = mockCallback,
            animatorBuilder = { mockAnimator }
        )

        val captor = argumentCaptor<Animator.AnimatorListener>()
        verify(mockAnimator).addListener(captor.capture())
        val listener = captor.value

        verify(mockCallback, never()).invoke()
        listener.onAnimationEnd(mockAnimator)
        verify(mockCallback, times(1)).invoke()
    }

    @Test
    fun testCancellation() {
        controller.startExitAnimations(
            view = mock(),
            doneCallback = mock(),
            animatorBuilder = { mockAnimator }
        )

        verify(mockAnimator, never()).cancel()
        controller.cancelAnimations()
        verify(mockAnimator, times(1)).cancel()
    }

    @Test
    fun testExitAfterStartWillCancel() {
        val mockStartAnimator: AnimatorSet = mock()
        val mockExitAnimator: AnimatorSet = mock()

        controller.startEntryAnimations(view = mock(), animatorBuilder = { mockStartAnimator })

        verify(mockStartAnimator, never()).cancel()

        controller.startExitAnimations(
            view = mock(),
            doneCallback = mock(),
            animatorBuilder = { mockExitAnimator }
        )

        // Verify that we cancelled the start animator in favor of the exit
        // animator.
        verify(mockStartAnimator, times(1)).cancel()
        verify(mockExitAnimator, never()).cancel()
    }
}

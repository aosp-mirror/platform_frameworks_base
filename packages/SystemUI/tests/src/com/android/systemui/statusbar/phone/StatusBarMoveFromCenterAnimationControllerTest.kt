package com.android.systemui.statusbar.phone

import android.graphics.Point
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.TestUnfoldTransitionProvider
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
class StatusBarMoveFromCenterAnimationControllerTest : SysuiTestCase() {

    @Mock
    private lateinit var windowManager: WindowManager

    @Mock
    private lateinit var display: Display

    private val view: View = View(context)
    private val progressProvider = TestUnfoldTransitionProvider()
    private val scopedProvider = ScopedUnfoldTransitionProgressProvider(progressProvider)

    private lateinit var controller: StatusBarMoveFromCenterAnimationController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(windowManager.defaultDisplay).thenReturn(display)
        `when`(display.rotation).thenReturn(Surface.ROTATION_0)
        `when`(display.getSize(any())).thenAnswer {
            val point = it.arguments[0] as Point
            point.x = 100
            point.y = 100
            Unit
        }

        scopedProvider.setReadyToHandleTransition(true)

        controller = StatusBarMoveFromCenterAnimationController(scopedProvider, windowManager)
    }

    @Test
    fun onTransitionProgressAndFinished_resetsTranslations() {
        controller.onViewsReady(arrayOf(view))

        progressProvider.onTransitionProgress(0.5f)
        progressProvider.onTransitionFinished()

        assertThat(view.translationX).isZero()
    }

    @Test
    fun onStatusBarWidthChangedWithNoTransitionBefore_noTranslation() {
        controller.onViewsReady(arrayOf(view))

        controller.onStatusBarWidthChanged()

        assertThat(view.translationX).isZero()
    }

    @Test
    fun onTransitionProgress_updatesTranslations() {
        controller.onViewsReady(arrayOf(view))

        progressProvider.onTransitionProgress(0.5f)

        assertThat(view.translationX).isNonZero()
    }

    @Test
    fun onTransitionProgress_whenDetached_doesNotUpdateTranslations() {
        controller.onViewsReady(arrayOf(view))
        controller.onViewDetached()

        progressProvider.onTransitionProgress(0.5f)

        assertThat(view.translationX).isZero()
    }

    @Test
    fun detachedAfterProgress_resetsTranslations() {
        controller.onViewsReady(arrayOf(view))
        progressProvider.onTransitionProgress(0.5f)

        controller.onViewDetached()

        assertThat(view.translationX).isZero()
    }

    @Test
    fun transitionFinished_viewReAttached_noChangesToTranslation() {
        controller.onViewsReady(arrayOf(view))
        progressProvider.onTransitionProgress(0.5f)
        progressProvider.onTransitionFinished()
        controller.onViewDetached()

        controller.onViewsReady(arrayOf(view))
        controller.onStatusBarWidthChanged()

        assertThat(view.translationX).isZero()
    }
}

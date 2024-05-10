package com.android.systemui.statusbar.phone

import android.graphics.Point
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.TestUnfoldTransitionProvider
import com.android.systemui.unfold.util.CurrentActivityTypeProvider
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
class StatusBarMoveFromCenterAnimationControllerTest : SysuiTestCase() {

    @Mock
    private lateinit var windowManager: WindowManager

    @Mock
    private lateinit var display: Display

    @Mock
    private lateinit var currentActivityTypeProvider: CurrentActivityTypeProvider

    private val view: View = View(context)
    private val progressProvider = TestUnfoldTransitionProvider()
    private val scopedProvider = ScopedUnfoldTransitionProgressProvider(progressProvider)

    private lateinit var controller: StatusBarMoveFromCenterAnimationController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(windowManager.defaultDisplay).thenReturn(display)
        whenever(display.rotation).thenReturn(Surface.ROTATION_0)
        whenever(display.getSize(any())).thenAnswer {
            val point = it.arguments[0] as Point
            point.x = 100
            point.y = 100
            Unit
        }

        scopedProvider.setReadyToHandleTransition(true)

        controller =
            StatusBarMoveFromCenterAnimationController(
                scopedProvider,
                currentActivityTypeProvider,
                windowManager
            )
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
    fun alpha_onLauncher_alphaDoesNotChange() {
        whenever(currentActivityTypeProvider.isHomeActivity).thenReturn(true)
        controller.onViewsReady(arrayOf(view))
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0.0f)
        assertThat(view.alpha).isEqualTo(1.0f)

        progressProvider.onTransitionProgress(1.0f)

        assertThat(view.alpha).isEqualTo(1.0f)
    }

    @Test
    fun alpha_NotOnLauncher_alphaChanges() {
        whenever(currentActivityTypeProvider.isHomeActivity).thenReturn(false)
        controller.onViewsReady(arrayOf(view))
        progressProvider.onTransitionStarted()
        assertThat(view.alpha).isEqualTo(1.0f)

        progressProvider.onTransitionProgress(0.5f)

        assertThat(view.alpha).isNotEqualTo(1.0f)
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

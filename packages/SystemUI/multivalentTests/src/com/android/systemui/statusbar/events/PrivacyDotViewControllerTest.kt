/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.events

import android.graphics.Point
import android.graphics.Rect
import android.testing.TestableLooper.RunWithLooper
import android.view.Display
import android.view.DisplayAdjustments
import android.view.View
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams.UNSPECIFIED_GRAVITY
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.statusbar.FakeStatusBarStateController
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_NONE
import com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_UPSIDE_DOWN
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class PrivacyDotViewControllerTest : SysuiTestCase() {

    private val mockDisplay = createMockDisplay()
    private val context = getContext().createDisplayContext(mockDisplay)

    private val testScope = TestScope()
    private val executor = InstantExecutor()
    private val statusBarStateController = FakeStatusBarStateController()
    private val configurationController = FakeConfigurationController()
    private val contentInsetsProvider = createMockContentInsetsProvider()

    private val topLeftView = initDotView()
    private val topRightView = initDotView()
    private val bottomLeftView = initDotView()
    private val bottomRightView = initDotView()

    private fun createAndInitializeController() =
        createController().also {
            it.initialize(topLeftView, topRightView, bottomLeftView, bottomRightView)
        }

    private fun createController() =
        PrivacyDotViewController(
                executor,
                testScope.backgroundScope,
                statusBarStateController,
                configurationController,
                contentInsetsProvider,
                animationScheduler = mock<SystemStatusAnimationScheduler>(),
                shadeInteractor = null
            )
            .also { it.setUiExecutor(executor) }

    @Test
    fun topMargin_topLeftView_basedOnSeascapeArea() {
        createAndInitializeController()

        assertThat(topLeftView.frameLayoutParams.topMargin)
            .isEqualTo(CONTENT_AREA_ROTATION_SEASCAPE.top)
    }

    @Test
    fun topMargin_topRightView_basedOnPortraitArea() {
        createAndInitializeController()

        assertThat(topRightView.frameLayoutParams.topMargin)
            .isEqualTo(CONTENT_AREA_ROTATION_NONE.top)
    }

    @Test
    fun topMargin_bottomLeftView_basedOnUpsideDownArea() {
        createAndInitializeController()

        assertThat(bottomLeftView.frameLayoutParams.topMargin)
            .isEqualTo(CONTENT_AREA_ROTATION_UPSIDE_DOWN.top)
    }

    @Test
    fun topMargin_bottomRightView_basedOnLandscapeArea() {
        createAndInitializeController()

        assertThat(bottomRightView.frameLayoutParams.topMargin)
            .isEqualTo(CONTENT_AREA_ROTATION_LANDSCAPE.top)
    }

    @Test
    fun height_topLeftView_basedOnSeascapeAreaHeight() {
        createAndInitializeController()

        assertThat(topLeftView.layoutParams.height)
            .isEqualTo(CONTENT_AREA_ROTATION_SEASCAPE.height())
    }

    @Test
    fun height_topRightView_basedOnPortraitAreaHeight() {
        createAndInitializeController()

        assertThat(topRightView.layoutParams.height).isEqualTo(CONTENT_AREA_ROTATION_NONE.height())
    }

    @Test
    fun height_bottomLeftView_basedOnUpsidedownAreaHeight() {
        createAndInitializeController()

        assertThat(bottomLeftView.layoutParams.height)
            .isEqualTo(CONTENT_AREA_ROTATION_UPSIDE_DOWN.height())
    }

    @Test
    fun height_bottomRightView_basedOnLandscapeAreaHeight() {
        createAndInitializeController()

        assertThat(bottomRightView.layoutParams.height)
            .isEqualTo(CONTENT_AREA_ROTATION_LANDSCAPE.height())
    }

    @Test
    fun width_topLeftView_ltr_basedOnDisplayHeightAndSeascapeArea() {
        createAndInitializeController()

        assertThat(topLeftView.layoutParams.width)
            .isEqualTo(DISPLAY_HEIGHT - CONTENT_AREA_ROTATION_SEASCAPE.right)
    }

    @Test
    fun width_topLeftView_rtl_basedOnPortraitArea() {
        createAndInitializeController()
        enableRtl()

        assertThat(topLeftView.layoutParams.width).isEqualTo(CONTENT_AREA_ROTATION_NONE.left)
    }

    @Test
    fun width_topRightView_ltr_basedOnPortraitArea() {
        createAndInitializeController()

        assertThat(topRightView.layoutParams.width)
            .isEqualTo(DISPLAY_WIDTH - CONTENT_AREA_ROTATION_NONE.right)
    }

    @Test
    fun width_topRightView_rtl_basedOnLandscapeArea() {
        createAndInitializeController()
        enableRtl()

        assertThat(topRightView.layoutParams.width).isEqualTo(CONTENT_AREA_ROTATION_LANDSCAPE.left)
    }

    @Test
    fun width_bottomRightView_ltr_basedOnDisplayHeightAndLandscapeArea() {
        createAndInitializeController()

        assertThat(bottomRightView.layoutParams.width)
            .isEqualTo(DISPLAY_HEIGHT - CONTENT_AREA_ROTATION_LANDSCAPE.right)
    }

    @Test
    fun width_bottomRightView_rtl_basedOnUpsideDown() {
        createAndInitializeController()
        enableRtl()

        assertThat(bottomRightView.layoutParams.width)
            .isEqualTo(CONTENT_AREA_ROTATION_UPSIDE_DOWN.left)
    }

    @Test
    fun width_bottomLeftView_ltr_basedOnDisplayWidthAndUpsideDownArea() {
        createAndInitializeController()

        assertThat(bottomLeftView.layoutParams.width)
            .isEqualTo(DISPLAY_WIDTH - CONTENT_AREA_ROTATION_UPSIDE_DOWN.right)
    }

    @Test
    fun width_bottomLeftView_rtl_basedOnSeascapeArea() {
        createAndInitializeController()
        enableRtl()

        assertThat(bottomLeftView.layoutParams.width).isEqualTo(CONTENT_AREA_ROTATION_SEASCAPE.left)
    }

    @Test
    fun initialize_rotationPortrait_activeCornerIsTopRight() {
        setRotation(ROTATION_NONE)

        val controller = createAndInitializeController()

        assertThat(controller.currentViewState.cornerIndex).isEqualTo(TOP_RIGHT)
        assertThat(controller.currentViewState.designatedCorner).isEqualTo(topRightView)
    }

    @Test
    fun initialize_rotationLandscape_activeCornerIsBottomRight() {
        setRotation(ROTATION_LANDSCAPE)

        val controller = createAndInitializeController()

        assertThat(controller.currentViewState.cornerIndex).isEqualTo(BOTTOM_RIGHT)
        assertThat(controller.currentViewState.designatedCorner).isEqualTo(bottomRightView)
    }

    @Test
    fun initialize_rotationSeascape_activeCornerIsTopLeft() {
        setRotation(ROTATION_SEASCAPE)

        val controller = createAndInitializeController()

        assertThat(controller.currentViewState.cornerIndex).isEqualTo(TOP_LEFT)
        assertThat(controller.currentViewState.designatedCorner).isEqualTo(topLeftView)
    }

    @Test
    fun initialize_rotationUpsideDown_activeCornerIsBottomLeft() {
        setRotation(ROTATION_UPSIDE_DOWN)

        val controller = createAndInitializeController()

        assertThat(controller.currentViewState.cornerIndex).isEqualTo(BOTTOM_LEFT)
        assertThat(controller.currentViewState.designatedCorner).isEqualTo(bottomLeftView)
    }

    @Test
    fun initialize_rotationPortrait_rtl_activeCornerIsTopLeft() {
        setRotation(ROTATION_NONE)

        enableRtl()
        val controller = createAndInitializeController()

        assertThat(controller.currentViewState.cornerIndex).isEqualTo(TOP_LEFT)
        assertThat(controller.currentViewState.designatedCorner).isEqualTo(topLeftView)
    }

    @Test
    fun initialize_rotationLandscape_rtl_activeCornerIsTopRight() {
        setRotation(ROTATION_LANDSCAPE)

        enableRtl()
        val controller = createAndInitializeController()

        assertThat(controller.currentViewState.cornerIndex).isEqualTo(TOP_RIGHT)
        assertThat(controller.currentViewState.designatedCorner).isEqualTo(topRightView)
    }

    @Test
    fun initialize_rotationSeascape_rtl_activeCornerIsBottomLeft() {
        setRotation(ROTATION_SEASCAPE)

        enableRtl()
        val controller = createAndInitializeController()

        assertThat(controller.currentViewState.cornerIndex).isEqualTo(BOTTOM_LEFT)
        assertThat(controller.currentViewState.designatedCorner).isEqualTo(bottomLeftView)
    }

    @Test
    fun initialize_rotationUpsideDown_rtl_activeCornerIsBottomRight() {
        setRotation(ROTATION_UPSIDE_DOWN)

        enableRtl()
        val controller = createAndInitializeController()

        assertThat(controller.currentViewState.cornerIndex).isEqualTo(BOTTOM_RIGHT)
        assertThat(controller.currentViewState.designatedCorner).isEqualTo(bottomRightView)
    }

    @Test
    fun initialize_newViews_gravityIsUpdated() {
        val newTopLeftView = initDotView()
        val newTopRightView = initDotView()
        val newBottomLeftView = initDotView()
        val newBottomRightView = initDotView()
        setRotation(ROTATION_LANDSCAPE) // Bottom right used in landscape

        val controller = createAndInitializeController()
        // Re-init with different views, but same rotation
        controller.initialize(
            newTopLeftView,
            newTopRightView,
            newBottomLeftView,
            newBottomRightView
        )

        assertThat((newBottomRightView.layoutParams as FrameLayout.LayoutParams).gravity)
            .isNotEqualTo(UNSPECIFIED_GRAVITY)
    }

    private fun setRotation(rotation: Int) {
        whenever(mockDisplay.rotation).thenReturn(rotation)
    }

    private fun initDotView(): View {
        val privacyDot = View(context).also { it.id = R.id.privacy_dot }
        return FrameLayout(context).also {
            it.layoutParams = FrameLayout.LayoutParams(/* width= */ 0, /* height= */ 0)
            it.addView(privacyDot)
        }
    }

    private fun enableRtl() {
        configurationController.notifyLayoutDirectionChanged(isRtl = true)
    }
}

private const val DISPLAY_WIDTH = 1234
private const val DISPLAY_HEIGHT = 2345
private val CONTENT_AREA_ROTATION_SEASCAPE = Rect(left = 10, top = 40, right = 990, bottom = 100)
private val CONTENT_AREA_ROTATION_NONE = Rect(left = 20, top = 30, right = 980, bottom = 100)
private val CONTENT_AREA_ROTATION_LANDSCAPE = Rect(left = 30, top = 20, right = 970, bottom = 100)
private val CONTENT_AREA_ROTATION_UPSIDE_DOWN = Rect(left = 40, top = 10, right = 960, bottom = 100)

private class InstantExecutor : DelayableExecutor {
    override fun execute(runnable: Runnable) {
        runnable.run()
    }

    override fun executeDelayed(runnable: Runnable, delay: Long, unit: TimeUnit) =
        runnable.apply { run() }

    override fun executeAtTime(runnable: Runnable, uptimeMillis: Long, unit: TimeUnit) =
        runnable.apply { run() }
}

private fun Rect(left: Int, top: Int, right: Int, bottom: Int) = Rect(left, top, right, bottom)

private val View.frameLayoutParams
    get() = layoutParams as FrameLayout.LayoutParams

private fun createMockDisplay() =
    mock<Display>().also { display ->
        whenever(display.getRealSize(any(Point::class.java))).thenAnswer { invocation ->
            val output = invocation.arguments[0] as Point
            output.x = DISPLAY_WIDTH
            output.y = DISPLAY_HEIGHT
            return@thenAnswer Unit
        }
        whenever(display.displayAdjustments).thenReturn(DisplayAdjustments())
    }

private fun createMockContentInsetsProvider() =
    mock<StatusBarContentInsetsProvider>().also {
        whenever(it.getStatusBarContentAreaForRotation(ROTATION_SEASCAPE))
            .thenReturn(CONTENT_AREA_ROTATION_SEASCAPE)
        whenever(it.getStatusBarContentAreaForRotation(ROTATION_NONE))
            .thenReturn(CONTENT_AREA_ROTATION_NONE)
        whenever(it.getStatusBarContentAreaForRotation(ROTATION_LANDSCAPE))
            .thenReturn(CONTENT_AREA_ROTATION_LANDSCAPE)
        whenever(it.getStatusBarContentAreaForRotation(ROTATION_UPSIDE_DOWN))
            .thenReturn(CONTENT_AREA_ROTATION_UPSIDE_DOWN)
    }

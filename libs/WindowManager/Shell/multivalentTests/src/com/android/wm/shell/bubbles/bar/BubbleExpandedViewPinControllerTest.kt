/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.wm.shell.bubbles.bar

import android.content.Context
import android.graphics.Insets
import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.animation.AnimatorTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.internal.protolog.common.ProtoLog
import com.android.wm.shell.R
import com.android.wm.shell.bubbles.BubblePositioner
import com.android.wm.shell.bubbles.DeviceConfig
import com.android.wm.shell.common.bubbles.BaseBubblePinController
import com.android.wm.shell.common.bubbles.BaseBubblePinController.Companion.DROP_TARGET_ALPHA_IN_DURATION
import com.android.wm.shell.common.bubbles.BaseBubblePinController.Companion.DROP_TARGET_ALPHA_OUT_DURATION
import com.android.wm.shell.common.bubbles.BubbleBarLocation
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for [BubbleExpandedViewPinController] */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BubbleExpandedViewPinControllerTest {

    companion object {
        @JvmField @ClassRule val animatorTestRule: AnimatorTestRule = AnimatorTestRule()

        const val SCREEN_WIDTH = 2000
        const val SCREEN_HEIGHT = 1000

        const val BUBBLE_BAR_HEIGHT = 50
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var positioner: BubblePositioner
    private lateinit var container: FrameLayout

    private lateinit var controller: BubbleExpandedViewPinController
    private lateinit var testListener: TestLocationChangeListener

    private val pointOnLeft = PointF(100f, 100f)
    private val pointOnRight = PointF(1900f, 500f)

    @Before
    fun setUp() {
        ProtoLog.REQUIRE_PROTOLOGTOOL = false
        container = FrameLayout(context)
        val windowManager = context.getSystemService(WindowManager::class.java)
        positioner = BubblePositioner(context, windowManager)
        positioner.setShowingInBubbleBar(true)
        val deviceConfig =
            DeviceConfig(
                windowBounds = Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT),
                isLargeScreen = true,
                isSmallTablet = false,
                isLandscape = true,
                isRtl = false,
                insets = Insets.of(10, 20, 30, 40)
            )
        positioner.update(deviceConfig)
        positioner.bubbleBarTopOnScreen =
            SCREEN_HEIGHT - deviceConfig.insets.bottom - BUBBLE_BAR_HEIGHT
        controller = BubbleExpandedViewPinController(context, container, positioner)
        testListener = TestLocationChangeListener()
        controller.setListener(testListener)
    }

    @After
    fun tearDown() {
        runOnMainSync { controller.onDragEnd() }
        waitForAnimateOut()
    }

    @Test
    fun drag_stayOnSameSide() {
        runOnMainSync {
            controller.onDragStart(initialLocationOnLeft = false)
            controller.onDragUpdate(pointOnRight.x, pointOnRight.y)
            controller.onDragEnd()
        }
        waitForAnimateIn()
        assertThat(dropTargetView).isNull()
        assertThat(testListener.locationChanges).isEmpty()
        assertThat(testListener.locationReleases).containsExactly(BubbleBarLocation.RIGHT)
    }

    @Test
    fun drag_toLeft() {
        // Drag to left, but don't finish
        runOnMainSync {
            controller.onDragStart(initialLocationOnLeft = false)
            controller.onDragUpdate(pointOnLeft.x, pointOnLeft.y)
        }
        waitForAnimateIn()

        assertThat(dropTargetView).isNotNull()
        assertThat(dropTargetView!!.alpha).isEqualTo(1f)

        val expectedDropTargetBounds = getExpectedDropTargetBounds(onLeft = true)
        assertThat(dropTargetView!!.layoutParams.width).isEqualTo(expectedDropTargetBounds.width())
        assertThat(dropTargetView!!.layoutParams.height)
            .isEqualTo(expectedDropTargetBounds.height())

        assertThat(testListener.locationChanges).containsExactly(BubbleBarLocation.LEFT)
        assertThat(testListener.locationReleases).isEmpty()

        // Finish the drag
        runOnMainSync { controller.onDragEnd() }
        assertThat(testListener.locationReleases).containsExactly(BubbleBarLocation.LEFT)
    }

    @Test
    fun drag_toLeftAndBackToRight() {
        // Drag to left
        runOnMainSync {
            controller.onDragStart(initialLocationOnLeft = false)
            controller.onDragUpdate(pointOnLeft.x, pointOnLeft.y)
        }
        waitForAnimateIn()
        assertThat(dropTargetView).isNotNull()

        // Drag to right
        runOnMainSync { controller.onDragUpdate(pointOnRight.x, pointOnRight.y) }
        // We have to wait for existing drop target to animate out and new to animate in
        waitForAnimateOut()
        waitForAnimateIn()

        assertThat(dropTargetView).isNotNull()
        assertThat(dropTargetView!!.alpha).isEqualTo(1f)

        val expectedDropTargetBounds = getExpectedDropTargetBounds(onLeft = false)
        assertThat(dropTargetView!!.layoutParams.width).isEqualTo(expectedDropTargetBounds.width())
        assertThat(dropTargetView!!.layoutParams.height)
            .isEqualTo(expectedDropTargetBounds.height())

        assertThat(testListener.locationChanges)
            .containsExactly(BubbleBarLocation.LEFT, BubbleBarLocation.RIGHT)
        assertThat(testListener.locationReleases).isEmpty()

        // Release the view
        runOnMainSync { controller.onDragEnd() }
        assertThat(testListener.locationReleases).containsExactly(BubbleBarLocation.RIGHT)
    }

    @Test
    fun drag_toLeftInExclusionRect() {
        runOnMainSync {
            controller.onDragStart(initialLocationOnLeft = false)
            // Exclusion rect is around the bottom center area of the screen
            controller.onDragUpdate(SCREEN_WIDTH / 2f - 50, SCREEN_HEIGHT - 100f)
        }
        waitForAnimateIn()
        assertThat(dropTargetView).isNull()
        assertThat(testListener.locationChanges).isEmpty()
        assertThat(testListener.locationReleases).isEmpty()

        runOnMainSync { controller.onDragEnd() }
        assertThat(testListener.locationReleases).containsExactly(BubbleBarLocation.RIGHT)
    }

    @Test
    fun toggleSetDropTargetHidden_dropTargetExists() {
        runOnMainSync {
            controller.onDragStart(initialLocationOnLeft = false)
            controller.onDragUpdate(pointOnLeft.x, pointOnLeft.y)
        }
        waitForAnimateIn()

        assertThat(dropTargetView).isNotNull()
        assertThat(dropTargetView!!.alpha).isEqualTo(1f)

        runOnMainSync { controller.setDropTargetHidden(true) }
        waitForAnimateOut()
        assertThat(dropTargetView).isNotNull()
        assertThat(dropTargetView!!.alpha).isEqualTo(0f)

        runOnMainSync { controller.setDropTargetHidden(false) }
        waitForAnimateIn()
        assertThat(dropTargetView).isNotNull()
        assertThat(dropTargetView!!.alpha).isEqualTo(1f)
    }

    @Test
    fun toggleSetDropTargetHidden_noDropTarget() {
        runOnMainSync { controller.setDropTargetHidden(true) }
        waitForAnimateOut()
        assertThat(dropTargetView).isNull()

        runOnMainSync { controller.setDropTargetHidden(false) }
        waitForAnimateIn()
        assertThat(dropTargetView).isNull()
    }

    @Test
    fun onDragEnd_dropTargetExists() {
        runOnMainSync {
            controller.onDragStart(initialLocationOnLeft = false)
            controller.onDragUpdate(pointOnLeft.x, pointOnLeft.y)
        }
        waitForAnimateIn()
        assertThat(dropTargetView).isNotNull()

        runOnMainSync { controller.onDragEnd() }
        waitForAnimateOut()
        assertThat(dropTargetView).isNull()
    }

    @Test
    fun onDragEnd_noDropTarget() {
        runOnMainSync { controller.onDragEnd() }
        waitForAnimateOut()
        assertThat(dropTargetView).isNull()
    }

    private val dropTargetView: View?
        get() = container.findViewById(R.id.bubble_bar_drop_target)

    private fun getExpectedDropTargetBounds(onLeft: Boolean): Rect =
        Rect().also {
            positioner.getBubbleBarExpandedViewBounds(onLeft, false /* isOveflowExpanded */, it)
        }

    private fun runOnMainSync(runnable: Runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable)
    }

    private fun waitForAnimateIn() {
        // Advance animator for on-device test
        runOnMainSync { animatorTestRule.advanceTimeBy(DROP_TARGET_ALPHA_IN_DURATION) }
    }

    private fun waitForAnimateOut() {
        // Advance animator for on-device test
        runOnMainSync { animatorTestRule.advanceTimeBy(DROP_TARGET_ALPHA_OUT_DURATION) }
    }

    internal class TestLocationChangeListener : BaseBubblePinController.LocationChangeListener {
        val locationChanges = mutableListOf<BubbleBarLocation>()
        val locationReleases = mutableListOf<BubbleBarLocation>()
        override fun onChange(location: BubbleBarLocation) {
            locationChanges.add(location)
        }

        override fun onRelease(location: BubbleBarLocation) {
            locationReleases.add(location)
        }
    }
}

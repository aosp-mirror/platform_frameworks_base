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
import com.android.wm.shell.bubbles.bar.BubbleBarDropTargetController.Companion.DROP_TARGET_ALPHA_IN_DURATION
import com.android.wm.shell.bubbles.bar.BubbleBarDropTargetController.Companion.DROP_TARGET_ALPHA_OUT_DURATION
import com.android.wm.shell.bubbles.bar.BubbleBarDropTargetController.Companion.DROP_TARGET_SCALE
import com.android.wm.shell.common.bubbles.BubbleBarLocation
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for [BubbleBarDropTargetController] */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BubbleBarDropTargetControllerTest {

    companion object {
        @JvmField @ClassRule val animatorTestRule: AnimatorTestRule = AnimatorTestRule()
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var controller: BubbleBarDropTargetController
    private lateinit var positioner: BubblePositioner
    private lateinit var container: FrameLayout

    @Before
    fun setUp() {
        ProtoLog.REQUIRE_PROTOLOGTOOL = false
        container = FrameLayout(context)
        val windowManager = context.getSystemService(WindowManager::class.java)
        positioner = BubblePositioner(context, windowManager)
        positioner.setShowingInBubbleBar(true)
        val deviceConfig =
            DeviceConfig(
                windowBounds = Rect(0, 0, 2000, 2600),
                isLargeScreen = true,
                isSmallTablet = false,
                isLandscape = true,
                isRtl = false,
                insets = Insets.of(10, 20, 30, 40)
            )
        positioner.update(deviceConfig)
        positioner.bubbleBarBounds = Rect(1800, 2400, 1970, 2560)

        controller = BubbleBarDropTargetController(context, container, positioner)
    }

    @Test
    fun show_moveLeftToRight_isVisibleWithExpectedBounds() {
        val expectedBoundsOnLeft = getExpectedDropTargetBounds(onLeft = true)
        val expectedBoundsOnRight = getExpectedDropTargetBounds(onLeft = false)

        runOnMainSync { controller.show(BubbleBarLocation.LEFT) }
        waitForAnimateIn()
        val viewOnLeft = getDropTargetView()
        assertThat(viewOnLeft).isNotNull()
        assertThat(viewOnLeft!!.alpha).isEqualTo(1f)
        assertThat(viewOnLeft.layoutParams.width).isEqualTo(expectedBoundsOnLeft.width())
        assertThat(viewOnLeft.layoutParams.height).isEqualTo(expectedBoundsOnLeft.height())
        assertThat(viewOnLeft.x).isEqualTo(expectedBoundsOnLeft.left)
        assertThat(viewOnLeft.y).isEqualTo(expectedBoundsOnLeft.top)

        runOnMainSync { controller.show(BubbleBarLocation.RIGHT) }
        waitForAnimateOut()
        waitForAnimateIn()
        val viewOnRight = getDropTargetView()
        assertThat(viewOnRight).isNotNull()
        assertThat(viewOnRight!!.alpha).isEqualTo(1f)
        assertThat(viewOnRight.layoutParams.width).isEqualTo(expectedBoundsOnRight.width())
        assertThat(viewOnRight.layoutParams.height).isEqualTo(expectedBoundsOnRight.height())
        assertThat(viewOnRight.x).isEqualTo(expectedBoundsOnRight.left)
        assertThat(viewOnRight.y).isEqualTo(expectedBoundsOnRight.top)
    }

    @Test
    fun toggleSetHidden_dropTargetShown_updatesAlpha() {
        runOnMainSync { controller.show(BubbleBarLocation.RIGHT) }
        waitForAnimateIn()
        val view = getDropTargetView()
        assertThat(view).isNotNull()
        assertThat(view!!.alpha).isEqualTo(1f)

        runOnMainSync { controller.setHidden(true) }
        waitForAnimateOut()
        val hiddenView = getDropTargetView()
        assertThat(hiddenView).isNotNull()
        assertThat(hiddenView!!.alpha).isEqualTo(0f)

        runOnMainSync { controller.setHidden(false) }
        waitForAnimateIn()
        val shownView = getDropTargetView()
        assertThat(shownView).isNotNull()
        assertThat(shownView!!.alpha).isEqualTo(1f)
    }

    @Test
    fun toggleSetHidden_dropTargetNotShown_viewNotCreated() {
        runOnMainSync { controller.setHidden(true) }
        waitForAnimateOut()
        assertThat(getDropTargetView()).isNull()
        runOnMainSync { controller.setHidden(false) }
        waitForAnimateIn()
        assertThat(getDropTargetView()).isNull()
    }

    @Test
    fun dismiss_dropTargetShown_viewRemoved() {
        runOnMainSync { controller.show(BubbleBarLocation.LEFT) }
        waitForAnimateIn()
        assertThat(getDropTargetView()).isNotNull()
        runOnMainSync { controller.dismiss() }
        waitForAnimateOut()
        assertThat(getDropTargetView()).isNull()
    }

    @Test
    fun dismiss_dropTargetNotShown_doesNothing() {
        runOnMainSync { controller.dismiss() }
        waitForAnimateOut()
        assertThat(getDropTargetView()).isNull()
    }

    private fun getDropTargetView(): View? = container.findViewById(R.id.bubble_bar_drop_target)

    private fun getExpectedDropTargetBounds(onLeft: Boolean): Rect {
        val rect = Rect()
        positioner.getBubbleBarExpandedViewBounds(onLeft, false /* isOveflowExpanded */, rect)
        // Scale the rect to expected size, but keep the center point the same
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        rect.scale(DROP_TARGET_SCALE)
        rect.offset(centerX - rect.centerX(), centerY - rect.centerY())
        return rect
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
}

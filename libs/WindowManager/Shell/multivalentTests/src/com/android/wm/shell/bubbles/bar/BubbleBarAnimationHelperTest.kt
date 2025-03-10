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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.animation.AnimatorTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.protolog.ProtoLog
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.bubbles.Bubble
import com.android.wm.shell.bubbles.BubbleExpandedViewManager
import com.android.wm.shell.bubbles.BubbleLogger
import com.android.wm.shell.bubbles.BubbleOverflow
import com.android.wm.shell.bubbles.BubblePositioner
import com.android.wm.shell.bubbles.DeviceConfig
import com.android.wm.shell.bubbles.FakeBubbleExpandedViewManager
import com.android.wm.shell.bubbles.FakeBubbleFactory
import com.android.wm.shell.bubbles.FakeBubbleTaskViewFactory
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for [BubbleBarAnimationHelper] */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BubbleBarAnimationHelperTest {

    companion object {
        @JvmField @ClassRule val animatorTestRule: AnimatorTestRule = AnimatorTestRule()

        const val SCREEN_WIDTH = 2000
        const val SCREEN_HEIGHT = 1000
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var animationHelper: BubbleBarAnimationHelper
    private lateinit var bubblePositioner: BubblePositioner
    private lateinit var expandedViewManager: BubbleExpandedViewManager
    private lateinit var bubbleLogger: BubbleLogger
    private lateinit var mainExecutor: TestShellExecutor
    private lateinit var bgExecutor: TestShellExecutor
    private lateinit var container: FrameLayout

    @Before
    fun setUp() {
        ProtoLog.REQUIRE_PROTOLOGTOOL = false
        ProtoLog.init()
        val windowManager = context.getSystemService(WindowManager::class.java)
        bubblePositioner = BubblePositioner(context, windowManager)
        bubblePositioner.setShowingInBubbleBar(true)
        val deviceConfig =
            DeviceConfig(
                windowBounds = Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT),
                isLargeScreen = true,
                isSmallTablet = false,
                isLandscape = true,
                isRtl = false,
                insets = Insets.of(10, 20, 30, 40),
            )
        bubblePositioner.update(deviceConfig)
        expandedViewManager = FakeBubbleExpandedViewManager()
        bubbleLogger = BubbleLogger(UiEventLoggerFake())

        mainExecutor = TestShellExecutor()
        bgExecutor = TestShellExecutor()

        container = FrameLayout(context)

        animationHelper = BubbleBarAnimationHelper(context, bubblePositioner)
    }

    @After
    fun tearDown() {
        bgExecutor.flushAll()
        mainExecutor.flushAll()
    }

    @Test
    fun animateSwitch_bubbleToBubble_oldHiddenNewShown() {
        val fromBubble = createBubble(key = "from").initialize(container)
        val toBubble = createBubble(key = "to").initialize(container)

        val semaphore = Semaphore(0)
        val after = Runnable { semaphore.release() }

        getInstrumentation().runOnMainSync {
            animationHelper.animateSwitch(fromBubble, toBubble, after)
            animatorTestRule.advanceTimeBy(1000)
        }
        getInstrumentation().waitForIdleSync()

        assertThat(semaphore.tryAcquire(5, TimeUnit.SECONDS)).isTrue()
        assertThat(fromBubble.bubbleBarExpandedView?.visibility).isEqualTo(View.INVISIBLE)
        assertThat(fromBubble.bubbleBarExpandedView?.alpha).isEqualTo(0f)
        assertThat(fromBubble.bubbleBarExpandedView?.isSurfaceZOrderedOnTop).isFalse()

        assertThat(toBubble.bubbleBarExpandedView?.visibility).isEqualTo(View.VISIBLE)
        assertThat(toBubble.bubbleBarExpandedView?.alpha).isEqualTo(1f)
        assertThat(toBubble.bubbleBarExpandedView?.isSurfaceZOrderedOnTop).isFalse()
    }

    @Test
    fun animateSwitch_bubbleToBubble_handleColorTransferred() {
        val fromBubble = createBubble(key = "from").initialize(container)
        fromBubble.bubbleBarExpandedView!!
            .handleView
            .updateHandleColor(/* isRegionDark= */ true, /* animated= */ false)
        val toBubble = createBubble(key = "to").initialize(container)

        getInstrumentation().runOnMainSync {
            animationHelper.animateSwitch(fromBubble, toBubble, /* afterAnimation= */ null)
            animatorTestRule.advanceTimeBy(1000)
        }
        getInstrumentation().waitForIdleSync()

        assertThat(toBubble.bubbleBarExpandedView!!.handleView.handleColor)
            .isEqualTo(fromBubble.bubbleBarExpandedView!!.handleView.handleColor)
    }

    @Test
    fun animateSwitch_bubbleToOverflow_oldHiddenNewShown() {
        val fromBubble = createBubble(key = "from").initialize(container)
        val overflow = createOverflow().initialize(container)

        val semaphore = Semaphore(0)
        val after = Runnable { semaphore.release() }

        getInstrumentation().runOnMainSync {
            animationHelper.animateSwitch(fromBubble, overflow, after)
            animatorTestRule.advanceTimeBy(1000)
        }
        getInstrumentation().waitForIdleSync()

        assertThat(semaphore.tryAcquire(5, TimeUnit.SECONDS)).isTrue()
        assertThat(fromBubble.bubbleBarExpandedView?.visibility).isEqualTo(View.INVISIBLE)
        assertThat(fromBubble.bubbleBarExpandedView?.alpha).isEqualTo(0f)
        assertThat(fromBubble.bubbleBarExpandedView?.isSurfaceZOrderedOnTop).isFalse()

        assertThat(overflow.bubbleBarExpandedView?.visibility).isEqualTo(View.VISIBLE)
        assertThat(overflow.bubbleBarExpandedView?.alpha).isEqualTo(1f)
    }

    @Test
    fun animateSwitch_overflowToBubble_oldHiddenNewShown() {
        val overflow = createOverflow().initialize(container)
        val toBubble = createBubble(key = "to").initialize(container)

        val semaphore = Semaphore(0)
        val after = Runnable { semaphore.release() }

        getInstrumentation().runOnMainSync {
            animationHelper.animateSwitch(overflow, toBubble, after)
            animatorTestRule.advanceTimeBy(1000)
        }
        getInstrumentation().waitForIdleSync()

        assertThat(semaphore.tryAcquire(5, TimeUnit.SECONDS)).isTrue()
        assertThat(overflow.bubbleBarExpandedView?.visibility).isEqualTo(View.INVISIBLE)
        assertThat(overflow.bubbleBarExpandedView?.alpha).isEqualTo(0f)

        assertThat(toBubble.bubbleBarExpandedView?.visibility).isEqualTo(View.VISIBLE)
        assertThat(toBubble.bubbleBarExpandedView?.alpha).isEqualTo(1f)
        assertThat(toBubble.bubbleBarExpandedView?.isSurfaceZOrderedOnTop).isFalse()
    }

    private fun createBubble(key: String): Bubble {
        val bubbleBarExpandedView =
            FakeBubbleFactory.createExpandedView(
                context,
                bubblePositioner,
                expandedViewManager,
                FakeBubbleTaskViewFactory(context, mainExecutor).create(),
                mainExecutor,
                bgExecutor,
                bubbleLogger,
            )
        val viewInfo = FakeBubbleFactory.createViewInfo(bubbleBarExpandedView)
        return FakeBubbleFactory.createChatBubble(context, key, viewInfo)
    }

    private fun createOverflow(): BubbleOverflow {
        val overflow = BubbleOverflow(context, bubblePositioner)
        overflow.initializeForBubbleBar(expandedViewManager, bubblePositioner, bubbleLogger)
        return overflow
    }

    private fun Bubble.initialize(container: ViewGroup): Bubble {
        getInstrumentation().runOnMainSync { container.addView(bubbleBarExpandedView) }
        // Mark taskView's visible
        bubbleBarExpandedView!!.onContentVisibilityChanged(true)
        return this
    }

    private fun BubbleOverflow.initialize(container: ViewGroup): BubbleOverflow {
        getInstrumentation().runOnMainSync { container.addView(bubbleBarExpandedView) }
        return this
    }
}

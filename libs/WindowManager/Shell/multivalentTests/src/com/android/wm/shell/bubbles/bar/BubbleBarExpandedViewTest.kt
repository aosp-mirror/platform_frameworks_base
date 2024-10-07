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

import android.app.ActivityManager
import android.content.Context
import android.graphics.Insets
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.internal.protolog.ProtoLog
import com.android.wm.shell.R
import com.android.wm.shell.bubbles.Bubble
import com.android.wm.shell.bubbles.BubbleData
import com.android.wm.shell.bubbles.BubbleExpandedViewManager
import com.android.wm.shell.bubbles.BubblePositioner
import com.android.wm.shell.bubbles.BubbleTaskView
import com.android.wm.shell.bubbles.BubbleTaskViewFactory
import com.android.wm.shell.bubbles.DeviceConfig
import com.android.wm.shell.bubbles.RegionSamplingProvider
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.shared.bubbles.BubbleBarLocation
import com.android.wm.shell.shared.handles.RegionSamplingHelper
import com.android.wm.shell.taskview.TaskView
import com.android.wm.shell.taskview.TaskViewTaskController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Collections
import java.util.concurrent.Executor

/** Tests for [BubbleBarExpandedViewTest] */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BubbleBarExpandedViewTest {
    companion object {
        const val SCREEN_WIDTH = 2000
        const val SCREEN_HEIGHT = 1000
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val windowManager = context.getSystemService(WindowManager::class.java)

    private lateinit var mainExecutor: TestExecutor
    private lateinit var bgExecutor: TestExecutor

    private lateinit var expandedViewManager: BubbleExpandedViewManager
    private lateinit var positioner: BubblePositioner
    private lateinit var bubbleTaskView: BubbleTaskView

    private lateinit var bubbleExpandedView: BubbleBarExpandedView
    private var testableRegionSamplingHelper: TestableRegionSamplingHelper? = null
    private var regionSamplingProvider: TestRegionSamplingProvider? = null

    @Before
    fun setUp() {
        ProtoLog.REQUIRE_PROTOLOGTOOL = false
        mainExecutor = TestExecutor()
        bgExecutor = TestExecutor()
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

        expandedViewManager = createExpandedViewManager()
        bubbleTaskView = FakeBubbleTaskViewFactory().create()

        val inflater = LayoutInflater.from(context)

        regionSamplingProvider = TestRegionSamplingProvider()

        bubbleExpandedView = (inflater.inflate(
            R.layout.bubble_bar_expanded_view, null, false /* attachToRoot */
        ) as BubbleBarExpandedView)
        bubbleExpandedView.initialize(
            expandedViewManager,
            positioner,
            false /* isOverflow */,
            bubbleTaskView,
            mainExecutor,
            bgExecutor,
            regionSamplingProvider
        )

        getInstrumentation().runOnMainSync(Runnable {
            bubbleExpandedView.onAttachedToWindow()
            // Helper should be created once attached to window
            testableRegionSamplingHelper = regionSamplingProvider!!.helper
        })
    }

    @After
    fun tearDown() {
        testableRegionSamplingHelper?.stopAndDestroy()
    }

    @Test
    fun testCreateSamplingHelper_onAttach() {
        assertThat(testableRegionSamplingHelper).isNotNull()
    }

    @Test
    fun testDestroySamplingHelper_onDetach() {
        bubbleExpandedView.onDetachedFromWindow()
        assertThat(testableRegionSamplingHelper!!.isDestroyed).isTrue()
    }

    @Test
    fun testStopSampling_onDragStart() {
        bubbleExpandedView.setContentVisibility(true)
        assertThat(testableRegionSamplingHelper!!.isStarted).isTrue()

        bubbleExpandedView.setDragging(true)
        assertThat(testableRegionSamplingHelper!!.isStopped).isTrue()
    }

    @Test
    fun testStartSampling_onDragEnd() {
        bubbleExpandedView.setDragging(true)
        bubbleExpandedView.setContentVisibility(true)
        assertThat(testableRegionSamplingHelper!!.isStopped).isTrue()

        bubbleExpandedView.setDragging(false)
        assertThat(testableRegionSamplingHelper!!.isStarted).isTrue()
    }

    @Test
    fun testStartSampling_onContentVisible() {
        bubbleExpandedView.setContentVisibility(true)
        assertThat(testableRegionSamplingHelper!!.setWindowVisible).isTrue()
        assertThat(testableRegionSamplingHelper!!.isStarted).isTrue()
    }

    @Test
    fun testStopSampling_onContentInvisible() {
        bubbleExpandedView.setContentVisibility(false)

        assertThat(testableRegionSamplingHelper!!.setWindowInvisible).isTrue()
        assertThat(testableRegionSamplingHelper!!.isStopped).isTrue()
    }

    @Test
    fun testSampling_startStopAnimating_visible() {
        bubbleExpandedView.isAnimating = true
        bubbleExpandedView.setContentVisibility(true)
        assertThat(testableRegionSamplingHelper!!.isStopped).isTrue()

        bubbleExpandedView.isAnimating = false
        assertThat(testableRegionSamplingHelper!!.isStarted).isTrue()
    }

    @Test
    fun testSampling_startStopAnimating_invisible() {
        bubbleExpandedView.isAnimating = true
        bubbleExpandedView.setContentVisibility(false)
        assertThat(testableRegionSamplingHelper!!.isStopped).isTrue()
        testableRegionSamplingHelper!!.reset()

        bubbleExpandedView.isAnimating = false
        assertThat(testableRegionSamplingHelper!!.isStopped).isTrue()
    }

    private inner class FakeBubbleTaskViewFactory : BubbleTaskViewFactory {
        override fun create(): BubbleTaskView {
            val taskViewTaskController = mock<TaskViewTaskController>()
            val taskView = TaskView(context, taskViewTaskController)
            val taskInfo = mock<ActivityManager.RunningTaskInfo>()
            whenever(taskViewTaskController.taskInfo).thenReturn(taskInfo)
            return BubbleTaskView(taskView, mainExecutor)
        }
    }

    private inner class TestRegionSamplingProvider : RegionSamplingProvider {

        lateinit var helper: TestableRegionSamplingHelper

        override fun createHelper(
            sampledView: View?,
            callback: RegionSamplingHelper.SamplingCallback?,
            backgroundExecutor: Executor?,
            mainExecutor: Executor?
        ): RegionSamplingHelper {
            helper = TestableRegionSamplingHelper(sampledView, callback, backgroundExecutor,
                mainExecutor)
            return helper
        }
    }

    private inner class TestableRegionSamplingHelper(
        sampledView: View?,
        samplingCallback: SamplingCallback?,
        backgroundExecutor: Executor?,
        mainExecutor: Executor?
    ) : RegionSamplingHelper(sampledView, samplingCallback, backgroundExecutor, mainExecutor) {

        var isStarted = false
        var isStopped = false
        var isDestroyed = false
        var setWindowVisible = false
        var setWindowInvisible = false

        override fun start(initialSamplingBounds: Rect) {
            super.start(initialSamplingBounds)
            isStarted = true
        }

        override fun stop() {
            super.stop()
            isStopped = true
        }

        override fun stopAndDestroy() {
            super.stopAndDestroy()
            isDestroyed = true
        }

        override fun setWindowVisible(visible: Boolean) {
            super.setWindowVisible(visible)
            if (visible) {
                setWindowVisible = true
            } else {
                setWindowInvisible = true
            }
        }

        fun reset() {
            isStarted = false
            isStopped = false
            isDestroyed = false
            setWindowVisible = false
            setWindowInvisible = false
        }
    }

    private fun createExpandedViewManager(): BubbleExpandedViewManager {
        return object : BubbleExpandedViewManager {
            override val overflowBubbles: List<Bubble>
                get() = Collections.emptyList()

            override fun setOverflowListener(listener: BubbleData.Listener) {
            }

            override fun collapseStack() {
            }

            override fun updateWindowFlagsForBackpress(intercept: Boolean) {
            }

            override fun promoteBubbleFromOverflow(bubble: Bubble) {
            }

            override fun removeBubble(key: String, reason: Int) {
            }

            override fun dismissBubble(bubble: Bubble, reason: Int) {
            }

            override fun setAppBubbleTaskId(key: String, taskId: Int) {
            }

            override fun isStackExpanded(): Boolean {
                return true
            }

            override fun isShowingAsBubbleBar(): Boolean {
                return true
            }

            override fun hideCurrentInputMethod() {
            }

            override fun updateBubbleBarLocation(location: BubbleBarLocation) {
            }
        }
    }

    private class TestExecutor : ShellExecutor {

        private val runnables: MutableList<Runnable> = mutableListOf()

        override fun execute(runnable: Runnable) {
            runnables.add(runnable)
        }

        override fun executeDelayed(runnable: Runnable, delayMillis: Long) {
            execute(runnable)
        }

        override fun removeCallbacks(runnable: Runnable?) {}

        override fun hasCallback(runnable: Runnable?): Boolean = false
    }
}
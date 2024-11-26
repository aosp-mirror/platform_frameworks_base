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
import android.content.pm.LauncherApps
import android.graphics.PointF
import android.os.Handler
import android.os.UserManager
import android.view.IWindowManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.animation.AnimatorTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.protolog.ProtoLog
import com.android.internal.statusbar.IStatusBarService
import com.android.wm.shell.R
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.WindowManagerShellWrapper
import com.android.wm.shell.bubbles.Bubble
import com.android.wm.shell.bubbles.BubbleController
import com.android.wm.shell.bubbles.BubbleData
import com.android.wm.shell.bubbles.BubbleDataRepository
import com.android.wm.shell.bubbles.BubbleEducationController
import com.android.wm.shell.bubbles.BubbleExpandedViewManager
import com.android.wm.shell.bubbles.BubbleLogger
import com.android.wm.shell.bubbles.BubblePositioner
import com.android.wm.shell.bubbles.BubbleTaskView
import com.android.wm.shell.bubbles.BubbleTaskViewFactory
import com.android.wm.shell.bubbles.Bubbles.SysuiProxy
import com.android.wm.shell.bubbles.FakeBubbleFactory
import com.android.wm.shell.bubbles.UiEventSubject.Companion.assertThat
import com.android.wm.shell.bubbles.animation.AnimatableScaleMatrix
import com.android.wm.shell.bubbles.properties.BubbleProperties
import com.android.wm.shell.bubbles.storage.BubblePersistentRepository
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayInsetsController
import com.android.wm.shell.common.FloatingContentCoordinator
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.common.TaskStackListenerImpl
import com.android.wm.shell.shared.TransactionPool
import com.android.wm.shell.shared.animation.PhysicsAnimatorTestUtils
import com.android.wm.shell.shared.bubbles.BubbleBarLocation
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.taskview.TaskView
import com.android.wm.shell.taskview.TaskViewTaskController
import com.android.wm.shell.taskview.TaskViewTransitions
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import org.junit.After
import java.util.Collections
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Tests for [BubbleBarLayerView] */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BubbleBarLayerViewTest {

    companion object {
        @JvmField @ClassRule
        val animatorTestRule: AnimatorTestRule = AnimatorTestRule()
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var bubbleBarLayerView: BubbleBarLayerView

    private lateinit var uiEventLoggerFake: UiEventLoggerFake

    private lateinit var bubbleController: BubbleController

    private lateinit var bubblePositioner: BubblePositioner

    private lateinit var bubble: Bubble

    @Before
    fun setUp() {
        ProtoLog.REQUIRE_PROTOLOGTOOL = false
        ProtoLog.init()
        PhysicsAnimatorTestUtils.prepareForTest()

        uiEventLoggerFake = UiEventLoggerFake()
        val bubbleLogger = BubbleLogger(uiEventLoggerFake)

        val mainExecutor = TestExecutor()
        val bgExecutor = TestExecutor()

        val windowManager = context.getSystemService(WindowManager::class.java)

        bubblePositioner = BubblePositioner(context, windowManager)
        bubblePositioner.setShowingInBubbleBar(true)

        val bubbleData =
            BubbleData(
                context,
                bubbleLogger,
                bubblePositioner,
                BubbleEducationController(context),
                mainExecutor,
                bgExecutor,
            )

        bubbleController =
            createBubbleController(
                bubbleData,
                windowManager,
                bubbleLogger,
                bubblePositioner,
                mainExecutor,
                bgExecutor,
            )
        bubbleController.asBubbles().setSysuiProxy(mock(SysuiProxy::class.java))
        // Flush so that proxy gets set
        mainExecutor.flushAll()

        bubbleBarLayerView = BubbleBarLayerView(context, bubbleController, bubbleData, bubbleLogger)

        val expandedViewManager = createExpandedViewManager()
        val bubbleTaskView = FakeBubbleTaskViewFactory(mainExecutor).create()
        val bubbleBarExpandedView =
            (LayoutInflater.from(context)
                    .inflate(R.layout.bubble_bar_expanded_view, null, false /* attachToRoot */)
                    as BubbleBarExpandedView)
                .apply {
                    initialize(
                        expandedViewManager,
                        bubblePositioner,
                        bubbleLogger,
                        false /* isOverflow */,
                        bubbleTaskView,
                        mainExecutor,
                        bgExecutor,
                        null, /* regionSamplingProvider */
                    )
                }

        val viewInfo = FakeBubbleFactory.createViewInfo(bubbleBarExpandedView)
        bubble = FakeBubbleFactory.createChatBubble(context, viewInfo = viewInfo)
    }

    @After
    fun tearDown() {
        PhysicsAnimatorTestUtils.tearDown()
        getInstrumentation().waitForIdleSync()
    }

    private fun createBubbleController(
        bubbleData: BubbleData,
        windowManager: WindowManager?,
        bubbleLogger: BubbleLogger,
        bubblePositioner: BubblePositioner,
        mainExecutor: TestExecutor,
        bgExecutor: TestExecutor,
    ): BubbleController {
        val shellInit = ShellInit(mainExecutor)
        val shellCommandHandler = ShellCommandHandler()
        val shellController =
            ShellController(
                context,
                shellInit,
                shellCommandHandler,
                mock<DisplayInsetsController>(),
                mainExecutor,
            )
        val surfaceSynchronizer = { obj: Runnable -> obj.run() }

        val bubbleDataRepository =
            BubbleDataRepository(
                mock<LauncherApps>(),
                mainExecutor,
                bgExecutor,
                BubblePersistentRepository(context),
            )

        return BubbleController(
            context,
            shellInit,
            shellCommandHandler,
            shellController,
            bubbleData,
            surfaceSynchronizer,
            FloatingContentCoordinator(),
            bubbleDataRepository,
            mock<IStatusBarService>(),
            windowManager,
            WindowManagerShellWrapper(mainExecutor),
            mock<UserManager>(),
            mock<LauncherApps>(),
            bubbleLogger,
            mock<TaskStackListenerImpl>(),
            mock<ShellTaskOrganizer>(),
            bubblePositioner,
            mock<DisplayController>(),
            null,
            null,
            mainExecutor,
            mock<Handler>(),
            bgExecutor,
            mock<TaskViewTransitions>(),
            mock<Transitions>(),
            SyncTransactionQueue(TransactionPool(), mainExecutor),
            mock<IWindowManager>(),
            mock<BubbleProperties>(),
        )
    }

    @Test
    fun testEventLogging_dismissExpandedViewViaDrag() {
        getInstrumentation().runOnMainSync { bubbleBarLayerView.showExpandedView(bubble) }
        assertThat(bubbleBarLayerView.findViewById<View>(R.id.bubble_bar_handle_view)).isNotNull()

        bubbleBarLayerView.dragController?.dragListener?.onReleased(true)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.logs[0].eventId)
            .isEqualTo(BubbleLogger.Event.BUBBLE_BAR_BUBBLE_DISMISSED_DRAG_EXP_VIEW.id)
        assertThat(uiEventLoggerFake.logs[0]).hasBubbleInfo(bubble)
    }

    @Test
    fun testEventLogging_dragExpandedViewLeft() {
        bubblePositioner.bubbleBarLocation = BubbleBarLocation.RIGHT

        getInstrumentation().runOnMainSync {
            bubbleBarLayerView.showExpandedView(bubble)
            bubble.bubbleBarExpandedView!!.onContentVisibilityChanged(true)
        }
        waitForExpandedViewAnimation()

        val handleView = bubbleBarLayerView.findViewById<View>(R.id.bubble_bar_handle_view)
        assertThat(handleView).isNotNull()

        // Drag from right to left
        handleView.dispatchTouchEvent(0L, MotionEvent.ACTION_DOWN, rightEdge())
        handleView.dispatchTouchEvent(10L, MotionEvent.ACTION_MOVE, leftEdge())
        handleView.dispatchTouchEvent(20L, MotionEvent.ACTION_UP, leftEdge())

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.logs[0].eventId)
            .isEqualTo(BubbleLogger.Event.BUBBLE_BAR_MOVED_LEFT_DRAG_EXP_VIEW.id)
        assertThat(uiEventLoggerFake.logs[0]).hasBubbleInfo(bubble)
    }

    @Test
    fun testEventLogging_dragExpandedViewRight() {
        bubblePositioner.bubbleBarLocation = BubbleBarLocation.LEFT

        getInstrumentation().runOnMainSync {
            bubbleBarLayerView.showExpandedView(bubble)
            bubble.bubbleBarExpandedView!!.onContentVisibilityChanged(true)
        }
        waitForExpandedViewAnimation()

        val handleView = bubbleBarLayerView.findViewById<View>(R.id.bubble_bar_handle_view)
        assertThat(handleView).isNotNull()

        // Drag from left to right
        handleView.dispatchTouchEvent(0L, MotionEvent.ACTION_DOWN, leftEdge())
        handleView.dispatchTouchEvent(10L, MotionEvent.ACTION_MOVE, rightEdge())
        handleView.dispatchTouchEvent(20L, MotionEvent.ACTION_UP, rightEdge())

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.logs[0].eventId)
            .isEqualTo(BubbleLogger.Event.BUBBLE_BAR_MOVED_RIGHT_DRAG_EXP_VIEW.id)
        assertThat(uiEventLoggerFake.logs[0]).hasBubbleInfo(bubble)
    }

    private fun leftEdge(): PointF {
        val screenSize = bubblePositioner.availableRect
        return PointF(screenSize.left.toFloat(), screenSize.height() / 2f)
    }

    private fun rightEdge(): PointF {
        val screenSize = bubblePositioner.availableRect
        return PointF(screenSize.right.toFloat(), screenSize.height() / 2f)
    }

    private fun waitForExpandedViewAnimation() {
        // wait for idle to allow the animation to start
        getInstrumentation().waitForIdleSync()
        getInstrumentation().runOnMainSync { animatorTestRule.advanceTimeBy(200) }
        PhysicsAnimatorTestUtils.blockUntilAnimationsEnd(
            AnimatableScaleMatrix.SCALE_X, AnimatableScaleMatrix.SCALE_Y)
    }

    private inner class FakeBubbleTaskViewFactory(private val mainExecutor: ShellExecutor) :
        BubbleTaskViewFactory {
        override fun create(): BubbleTaskView {
            val taskViewTaskController = mock<TaskViewTaskController>()
            val taskView = TaskView(context, taskViewTaskController)
            val taskInfo = mock<ActivityManager.RunningTaskInfo>()
            whenever(taskViewTaskController.taskInfo).thenReturn(taskInfo)
            return BubbleTaskView(taskView, mainExecutor)
        }
    }

    private fun createExpandedViewManager(): BubbleExpandedViewManager {
        return object : BubbleExpandedViewManager {
            override val overflowBubbles: List<Bubble>
                get() = Collections.emptyList()

            override fun setOverflowListener(listener: BubbleData.Listener) {}

            override fun collapseStack() {}

            override fun updateWindowFlagsForBackpress(intercept: Boolean) {}

            override fun promoteBubbleFromOverflow(bubble: Bubble) {}

            override fun removeBubble(key: String, reason: Int) {}

            override fun dismissBubble(bubble: Bubble, reason: Int) {}

            override fun setAppBubbleTaskId(key: String, taskId: Int) {}

            override fun isStackExpanded(): Boolean {
                return true
            }

            override fun isShowingAsBubbleBar(): Boolean {
                return true
            }

            override fun hideCurrentInputMethod() {}

            override fun updateBubbleBarLocation(location: BubbleBarLocation, source: Int) {}
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

        fun flushAll() {
            while (runnables.isNotEmpty()) {
                runnables.removeAt(0).run()
            }
        }
    }

    private fun View.dispatchTouchEvent(eventTime: Long, action: Int, point: PointF) {
        val event = MotionEvent.obtain(0L, eventTime, action, point.x, point.y, 0)
        getInstrumentation().runOnMainSync { dispatchTouchEvent(event) }
    }
}

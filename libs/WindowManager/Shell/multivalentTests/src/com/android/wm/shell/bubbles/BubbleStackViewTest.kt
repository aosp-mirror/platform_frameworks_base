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

package com.android.wm.shell.bubbles

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.view.IWindowManager
import android.view.WindowManager
import android.view.WindowManagerGlobal
import androidx.test.annotation.UiThreadTest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.protolog.common.ProtoLog
import com.android.launcher3.icons.BubbleIconFactory
import com.android.wm.shell.R
import com.android.wm.shell.bubbles.Bubbles.SysuiProxy
import com.android.wm.shell.common.FloatingContentCoordinator
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.taskview.TaskView
import com.android.wm.shell.taskview.TaskViewTaskController
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

/** Unit tests for [BubbleStackView]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BubbleStackViewTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var positioner: BubblePositioner
    private lateinit var iconFactory: BubbleIconFactory
    private lateinit var expandedViewManager: FakeBubbleExpandedViewManager
    private lateinit var bubbleStackView: BubbleStackView
    private lateinit var shellExecutor: ShellExecutor
    private lateinit var windowManager: IWindowManager
    private lateinit var bubbleTaskViewFactory: BubbleTaskViewFactory
    private lateinit var bubbleData: BubbleData

    @Before
    fun setUp() {
        // Disable protolog tool when running the tests from studio
        ProtoLog.REQUIRE_PROTOLOGTOOL = false
        windowManager = WindowManagerGlobal.getWindowManagerService()!!
        shellExecutor = TestShellExecutor()
        val windowManager = context.getSystemService(WindowManager::class.java)
        iconFactory =
            BubbleIconFactory(
                context,
                context.resources.getDimensionPixelSize(R.dimen.bubble_size),
                context.resources.getDimensionPixelSize(R.dimen.bubble_badge_size),
                Color.BLACK,
                context.resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.importance_ring_stroke_width
                )
            )
        positioner = BubblePositioner(context, windowManager)
        val bubbleStackViewManager = FakeBubbleStackViewManager()
        bubbleData =
            BubbleData(
                context,
                BubbleLogger(UiEventLoggerFake()),
                positioner,
                BubbleEducationController(context),
                shellExecutor
            )

        val sysuiProxy = mock<SysuiProxy>()
        expandedViewManager = FakeBubbleExpandedViewManager()
        bubbleTaskViewFactory = FakeBubbleTaskViewFactory()
        bubbleStackView =
            BubbleStackView(
                context,
                bubbleStackViewManager,
                positioner,
                bubbleData,
                null,
                FloatingContentCoordinator(),
                { sysuiProxy },
                shellExecutor
            )
    }

    @UiThreadTest
    @Test
    fun addBubble() {
        val bubble = createAndInflateBubble()
        bubbleStackView.addBubble(bubble)
        assertThat(bubbleStackView.bubbleCount).isEqualTo(1)
    }

    @UiThreadTest
    @Test
    fun tapBubbleToExpand() {
        val bubble = createAndInflateBubble()
        bubbleStackView.addBubble(bubble)
        assertThat(bubbleStackView.bubbleCount).isEqualTo(1)

        bubble.iconView!!.performClick()
        // we're checking the expanded state in BubbleData because that's the source of truth. This
        // will eventually propagate an update back to the stack view, but setting the entire
        // pipeline is outside the scope of a unit test.
        assertThat(bubbleData.isExpanded).isTrue()
    }

    private fun createAndInflateBubble(): Bubble {
        val intent = Intent(Intent.ACTION_VIEW).setPackage(context.packageName)
        val icon = Icon.createWithResource(context.resources, R.drawable.bubble_ic_overflow_button)
        val bubble = Bubble.createAppBubble(intent, UserHandle(1), icon, directExecutor())
        bubble.setInflateSynchronously(true)
        bubbleData.notificationEntryUpdated(bubble, true, false)

        val semaphore = Semaphore(0)
        val callback: BubbleViewInfoTask.Callback =
            BubbleViewInfoTask.Callback { semaphore.release() }
        bubble.inflate(
            callback,
            context,
            expandedViewManager,
            bubbleTaskViewFactory,
            positioner,
            bubbleStackView,
            null,
            iconFactory,
            false
        )

        assertThat(semaphore.tryAcquire(5, TimeUnit.SECONDS)).isTrue()
        assertThat(bubble.isInflated).isTrue()
        return bubble
    }

    private class FakeBubbleStackViewManager : BubbleStackViewManager {

        override fun onAllBubblesAnimatedOut() {}

        override fun updateWindowFlagsForBackpress(interceptBack: Boolean) {}

        override fun checkNotificationPanelExpandedState(callback: Consumer<Boolean>) {}

        override fun hideCurrentInputMethod() {}
    }

    private class TestShellExecutor : ShellExecutor {

        override fun execute(runnable: Runnable) {
            runnable.run()
        }

        override fun executeDelayed(r: Runnable, delayMillis: Long) {
            r.run()
        }

        override fun removeCallbacks(r: Runnable) {}

        override fun hasCallback(r: Runnable): Boolean = false
    }

    private inner class FakeBubbleTaskViewFactory : BubbleTaskViewFactory {
        override fun create(): BubbleTaskView {
            val taskViewTaskController = mock<TaskViewTaskController>()
            val taskView = TaskView(context, taskViewTaskController)
            return BubbleTaskView(taskView, shellExecutor)
        }
    }

    private inner class FakeBubbleExpandedViewManager : BubbleExpandedViewManager {

        override val overflowBubbles: List<Bubble>
            get() = emptyList()

        override fun setOverflowListener(listener: BubbleData.Listener) {}

        override fun collapseStack() {}

        override fun updateWindowFlagsForBackpress(intercept: Boolean) {}

        override fun promoteBubbleFromOverflow(bubble: Bubble) {}

        override fun removeBubble(key: String, reason: Int) {}

        override fun dismissBubble(bubble: Bubble, reason: Int) {}

        override fun setAppBubbleTaskId(key: String, taskId: Int) {}

        override fun isStackExpanded(): Boolean = false

        override fun isShowingAsBubbleBar(): Boolean = false
    }
}

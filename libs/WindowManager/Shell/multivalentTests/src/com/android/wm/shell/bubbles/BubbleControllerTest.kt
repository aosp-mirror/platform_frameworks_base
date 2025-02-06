/*
 * Copyright (C) 2025 The Android Open Source Project
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
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import android.view.IWindowManager
import android.view.InsetsSource
import android.view.InsetsState
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.protolog.ProtoLog
import com.android.internal.statusbar.IStatusBarService
import com.android.wm.shell.R
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.bubbles.Bubbles.BubbleExpandListener
import com.android.wm.shell.bubbles.Bubbles.SysuiProxy
import com.android.wm.shell.bubbles.storage.BubblePersistentRepository
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayImeController
import com.android.wm.shell.common.DisplayInsetsController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.FloatingContentCoordinator
import com.android.wm.shell.common.ImeListener
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.common.TaskStackListenerImpl
import com.android.wm.shell.common.TestShellExecutor
import com.android.wm.shell.common.TestSyncExecutor
import com.android.wm.shell.draganddrop.DragAndDropController
import com.android.wm.shell.shared.TransactionPool
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.taskview.TaskViewRepository
import com.android.wm.shell.taskview.TaskViewTransitions
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.util.Optional
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Tests for [BubbleController] */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BubbleControllerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var bubbleController: BubbleController
    private lateinit var bubblePositioner: BubblePositioner
    private lateinit var uiEventLoggerFake: UiEventLoggerFake
    private lateinit var bubbleLogger: BubbleLogger
    private lateinit var mainExecutor: TestShellExecutor
    private lateinit var bgExecutor: TestShellExecutor
    private lateinit var bubbleData: BubbleData
    private lateinit var eduController: BubbleEducationController
    private lateinit var displayController: DisplayController
    private lateinit var displayImeController: DisplayImeController
    private lateinit var displayInsetsController: DisplayInsetsController
    private lateinit var imeListener: ImeListener

    @Before
    fun setUp() {
        ProtoLog.REQUIRE_PROTOLOGTOOL = false
        ProtoLog.init()

        uiEventLoggerFake = UiEventLoggerFake()
        bubbleLogger = BubbleLogger(uiEventLoggerFake)
        eduController = BubbleEducationController(context)

        mainExecutor = TestShellExecutor()
        bgExecutor = TestShellExecutor()

        // Tests don't have permission to add our window to windowManager, so we mock it :(
        val windowManager = mock<WindowManager>()
        val realWindowManager = context.getSystemService(WindowManager::class.java)!!
        // But we do want the metrics from the real one
        whenever(windowManager.currentWindowMetrics)
            .thenReturn(realWindowManager.currentWindowMetrics)
        whenever(windowManager.defaultDisplay).thenReturn(realWindowManager.defaultDisplay)

        bubblePositioner = BubblePositioner(context, windowManager)

        bubbleData =
            BubbleData(
                context,
                bubbleLogger,
                bubblePositioner,
                eduController,
                mainExecutor,
                bgExecutor
            )
        displayController = mock<DisplayController>()
        displayImeController = mock<DisplayImeController>()
        displayInsetsController = mock<DisplayInsetsController>()

        bubbleController =
            createBubbleController(
                bubbleData,
                windowManager,
                bubbleLogger,
                bubblePositioner,
                mainExecutor,
                bgExecutor,
            )
        bubbleController.asBubbles().setSysuiProxy(mock<SysuiProxy>())
        // Flush so that proxy gets set
        mainExecutor.flushAll()

        whenever(displayController.getDisplayLayout(anyInt()))
            .thenReturn(DisplayLayout(context, realWindowManager.defaultDisplay))
        val insetsChangedListenerCaptor = argumentCaptor<ImeListener>()
        verify(displayInsetsController)
            .addInsetsChangedListener(anyInt(), insetsChangedListenerCaptor.capture())
        imeListener = insetsChangedListenerCaptor.lastValue
    }

    @After
    fun tearDown() {
        getInstrumentation().waitForIdleSync()
    }

    @Test
    fun showOrHideNotesBubble_createsNoteBubble() {
        val intent = Intent(context, TestActivity::class.java)
        intent.setPackage(context.packageName)
        val user = UserHandle.of(0)
        val expectedKey = Bubble.getNoteBubbleKeyForApp(intent.getPackage(), user)

        getInstrumentation().runOnMainSync {
            bubbleController.showOrHideNotesBubble(intent, user, mock<Icon>())
        }
        getInstrumentation().waitForIdleSync()

        assertThat(bubbleController.hasBubbles()).isTrue()
        assertThat(bubbleData.getAnyBubbleWithKey(expectedKey)).isNotNull()
        assertThat(bubbleData.getAnyBubbleWithKey(expectedKey)!!.isNote).isTrue()
    }

    @Test
    fun onDeviceLocked_expanded_imeHidden_shouldCollapseImmediately() {
        val bubble = createBubble("key")
        bubblePositioner.setImeVisible(false, 0)
        getInstrumentation().runOnMainSync {
            bubbleController.inflateAndAdd(
                bubble,
                /* suppressFlyout= */ true,
                /* showInShade= */ true
            )
        }
        assertThat(bubbleData.hasBubbles()).isTrue()

        // expand and lock the device
        getInstrumentation().runOnMainSync {
            bubbleController.expandStackAndSelectBubble(bubble)
            assertThat(bubbleData.isExpanded).isTrue()
            bubbleController.onStatusBarStateChanged(/* isShade= */ false)
        }
        // verify that we collapsed immediately, since the IME is hidden
        assertThat(bubbleData.isExpanded).isFalse()
    }

    @Test
    fun onDeviceLocked_expanded_imeVisible_shouldHideImeBeforeCollapsing() {
        val bubble = createBubble("key")
        getInstrumentation().runOnMainSync {
            bubbleController.inflateAndAdd(
                bubble,
                /* suppressFlyout= */ true,
                /* showInShade= */ true
            )
        }
        assertThat(bubbleData.hasBubbles()).isTrue()

        // expand and show the IME. then lock the device
        val imeVisibleInsetsState = createFakeInsetsState(imeVisible = true)
        getInstrumentation().runOnMainSync {
            bubbleController.expandStackAndSelectBubble(bubble)
            assertThat(bubbleData.isExpanded).isTrue()
            imeListener.insetsChanged(imeVisibleInsetsState)
            assertThat(bubblePositioner.isImeVisible).isTrue()
            bubbleController.onStatusBarStateChanged(/* isShade= */ false)
        }
        // check that we haven't actually started collapsing because we weren't notified yet that
        // the IME is hidden
        assertThat(bubbleData.isExpanded).isTrue()
        // collapsing while the device is locked goes through display ime controller
        verify(displayImeController).hideImeForBubblesWhenLocked(anyInt())

        // notify that the IME was hidden
        val imeHiddenInsetsState = createFakeInsetsState(imeVisible = false)
        getInstrumentation().runOnMainSync { imeListener.insetsChanged(imeHiddenInsetsState) }
        assertThat(bubblePositioner.isImeVisible).isFalse()
        // bubbles should be collapsed now
        assertThat(bubbleData.isExpanded).isFalse()
    }

    @Test
    fun onDeviceLocked_whileHidingImeDuringCollapse() {
        val bubble = createBubble("key")
        val expandListener = FakeBubbleExpandListener()
        bubbleController.setExpandListener(expandListener)

        getInstrumentation().runOnMainSync {
            bubbleController.inflateAndAdd(
                bubble,
                /* suppressFlyout= */ true,
                /* showInShade= */ true
            )
        }
        assertThat(bubbleData.hasBubbles()).isTrue()

        // expand
        getInstrumentation().runOnMainSync {
            bubbleController.expandStackAndSelectBubble(bubble)
            assertThat(bubbleData.isExpanded).isTrue()
            mainExecutor.flushAll()
        }

        assertThat(expandListener.bubblesExpandedState).isEqualTo(mapOf("key" to true))

        // show the IME
        val imeVisibleInsetsState = createFakeInsetsState(imeVisible = true)
        getInstrumentation().runOnMainSync { imeListener.insetsChanged(imeVisibleInsetsState) }

        assertThat(bubblePositioner.isImeVisible).isTrue()

        // collapse the stack
        getInstrumentation().runOnMainSync { bubbleController.collapseStack() }
        assertThat(bubbleData.isExpanded).isFalse()
        // since we started to collapse while the IME was visible, we will wait to be notified that
        // the IME is hidden before completing the collapse. check that the expand listener was not
        // yet called
        assertThat(expandListener.bubblesExpandedState).isEqualTo(mapOf("key" to true))

        // lock the device during this state
        getInstrumentation().runOnMainSync {
            bubbleController.onStatusBarStateChanged(/* isShade= */ false)
        }
        verify(displayImeController).hideImeForBubblesWhenLocked(anyInt())

        // notify that the IME is hidden
        val imeHiddenInsetsState = createFakeInsetsState(imeVisible = false)
        getInstrumentation().runOnMainSync { imeListener.insetsChanged(imeHiddenInsetsState) }
        assertThat(bubblePositioner.isImeVisible).isFalse()
        // verify the collapse action completed
        assertThat(expandListener.bubblesExpandedState).isEqualTo(mapOf("key" to false))
    }

    private fun createBubble(key: String): Bubble {
        val icon = Icon.createWithResource(context.resources, R.drawable.bubble_ic_overflow_button)
        val shortcutInfo = ShortcutInfo.Builder(context, "fakeId").setIcon(icon).build()
        val bubble =
            Bubble(
                key,
                shortcutInfo,
                /* desiredHeight= */ 0,
                Resources.ID_NULL,
                "title",
                /* taskId= */ 0,
                "locus",
                /* isDismissable= */ true,
                directExecutor(),
                directExecutor()
            ) {}
        return bubble
    }

    private fun createFakeInsetsState(imeVisible: Boolean): InsetsState {
        val insetsState = InsetsState()
        if (imeVisible) {
            insetsState
                .getOrCreateSource(InsetsSource.ID_IME, WindowInsets.Type.ime())
                .setFrame(Rect(0, 100, 100, 200))
                .setVisible(true)
        }
        return insetsState
    }

    private fun createBubbleController(
        bubbleData: BubbleData,
        windowManager: WindowManager,
        bubbleLogger: BubbleLogger,
        bubblePositioner: BubblePositioner,
        mainExecutor: TestShellExecutor,
        bgExecutor: TestShellExecutor,
    ): BubbleController {
        val shellInit = ShellInit(mainExecutor)
        val shellCommandHandler = ShellCommandHandler()
        val shellController =
            ShellController(
                context,
                shellInit,
                shellCommandHandler,
                displayInsetsController,
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

        val shellTaskOrganizer =
            ShellTaskOrganizer(
                mock<ShellInit>(),
                ShellCommandHandler(),
                null,
                Optional.empty(),
                Optional.empty(),
                TestSyncExecutor()
            )

        val resizeChecker = ResizabilityChecker { _, _, _ -> true }

        val bubbleController =
            BubbleController(
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
                displayInsetsController,
                displayImeController,
                mock<UserManager>(),
                mock<LauncherApps>(),
                bubbleLogger,
                mock<TaskStackListenerImpl>(),
                shellTaskOrganizer,
                bubblePositioner,
                displayController,
                Optional.empty(),
                mock<DragAndDropController>(),
                mainExecutor,
                mock<Handler>(),
                bgExecutor,
                mock<TaskViewRepository>(),
                mock<TaskViewTransitions>(),
                mock<Transitions>(),
                SyncTransactionQueue(TransactionPool(), mainExecutor),
                mock<IWindowManager>(),
                resizeChecker,
            )
        bubbleController.setInflateSynchronously(true)
        bubbleController.onInit()

        return bubbleController
    }

    private class FakeBubbleExpandListener : BubbleExpandListener {
        val bubblesExpandedState = mutableMapOf<String, Boolean>()

        override fun onBubbleExpandChanged(isExpanding: Boolean, key: String) {
            bubblesExpandedState[key] = isExpanding
        }
    }
}

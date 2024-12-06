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
import android.content.pm.LauncherApps
import android.graphics.Insets
import android.graphics.Rect
import android.os.Handler
import android.os.UserManager
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.view.IWindowManager
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.protolog.ProtoLog
import com.android.internal.statusbar.IStatusBarService
import com.android.wm.shell.Flags
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.WindowManagerShellWrapper
import com.android.wm.shell.bubbles.Bubbles.SysuiProxy
import com.android.wm.shell.bubbles.properties.ProdBubbleProperties
import com.android.wm.shell.bubbles.storage.BubblePersistentRepository
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayInsetsController
import com.android.wm.shell.common.FloatingContentCoordinator
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.common.TaskStackListenerImpl
import com.android.wm.shell.draganddrop.DragAndDropController
import com.android.wm.shell.shared.TransactionPool
import com.android.wm.shell.shared.bubbles.BubbleBarLocation
import com.android.wm.shell.shared.bubbles.BubbleBarUpdate
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.taskview.TaskViewTransitions
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Optional

/** Tests for [BubbleController] when using bubble bar */
@SmallTest
@EnableFlags(Flags.FLAG_ENABLE_BUBBLE_BAR)
@RunWith(AndroidJUnit4::class)
class BubbleControllerBubbleBarTest {

    companion object {
        private const val SCREEN_WIDTH = 2000
        private const val SCREEN_HEIGHT = 1000
    }

    @get:Rule val setFlagsRule = SetFlagsRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var bubbleController: BubbleController
    private lateinit var uiEventLoggerFake: UiEventLoggerFake
    private lateinit var bubblePositioner: BubblePositioner
    private lateinit var bubbleData: BubbleData
    private lateinit var mainExecutor: TestShellExecutor
    private lateinit var bgExecutor: TestShellExecutor

    @Before
    fun setUp() {
        ProtoLog.REQUIRE_PROTOLOGTOOL = false
        ProtoLog.init()

        mainExecutor = TestShellExecutor()
        bgExecutor = TestShellExecutor()

        uiEventLoggerFake = UiEventLoggerFake()
        val bubbleLogger = BubbleLogger(uiEventLoggerFake)

        val deviceConfig =
            DeviceConfig(
                windowBounds = Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT),
                isLargeScreen = true,
                isSmallTablet = false,
                isLandscape = true,
                isRtl = false,
                insets = Insets.of(10, 20, 30, 40),
            )

        bubblePositioner = BubblePositioner(context, deviceConfig)
        bubblePositioner.isShowingInBubbleBar = true

        bubbleData =
            BubbleData(
                context,
                bubbleLogger,
                bubblePositioner,
                BubbleEducationController(context),
                mainExecutor,
                bgExecutor,
            )

        val shellInit = ShellInit(mainExecutor)

        bubbleController =
            createBubbleController(
                shellInit,
                bubbleData,
                bubbleLogger,
                bubblePositioner,
                mainExecutor,
                bgExecutor,
            )
        bubbleController.asBubbles().setSysuiProxy(Mockito.mock(SysuiProxy::class.java))

        shellInit.init()

        mainExecutor.flushAll()
        bgExecutor.flushAll()

        bubbleController.registerBubbleStateListener(FakeBubblesStateListener())
    }

    @After
    fun tearDown() {
        mainExecutor.flushAll()
        bgExecutor.flushAll()
    }

    @Test
    fun testEventLogging_bubbleBar_dragBarLeft() {
        addBubble()

        bubblePositioner.bubbleBarLocation = BubbleBarLocation.RIGHT

        bubbleController.setBubbleBarLocation(
            BubbleBarLocation.LEFT,
            BubbleBarLocation.UpdateSource.DRAG_BAR,
        )

        // 2 events: add bubble + drag event
        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(2)
        assertThat(uiEventLoggerFake.eventId(1))
            .isEqualTo(BubbleLogger.Event.BUBBLE_BAR_MOVED_LEFT_DRAG_BAR.id)
    }

    @Test
    fun testEventLogging_bubbleBar_dragBarRight() {
        addBubble()

        bubblePositioner.bubbleBarLocation = BubbleBarLocation.LEFT

        bubbleController.setBubbleBarLocation(
            BubbleBarLocation.RIGHT,
            BubbleBarLocation.UpdateSource.DRAG_BAR,
        )

        // 2 events: add bubble + drag event
        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(2)
        assertThat(uiEventLoggerFake.eventId(1))
            .isEqualTo(BubbleLogger.Event.BUBBLE_BAR_MOVED_RIGHT_DRAG_BAR.id)
    }

    @Test
    fun testEventLogging_bubbleBar_dragBubbleLeft() {
        addBubble()

        bubblePositioner.bubbleBarLocation = BubbleBarLocation.RIGHT

        bubbleController.setBubbleBarLocation(
            BubbleBarLocation.LEFT,
            BubbleBarLocation.UpdateSource.DRAG_BUBBLE,
        )

        // 2 events: add bubble + drag event
        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(2)
        assertThat(uiEventLoggerFake.eventId(1))
            .isEqualTo(BubbleLogger.Event.BUBBLE_BAR_MOVED_LEFT_DRAG_BUBBLE.id)
    }

    @Test
    fun testEventLogging_bubbleBar_dragBubbleRight() {
        addBubble()

        bubblePositioner.bubbleBarLocation = BubbleBarLocation.LEFT

        bubbleController.setBubbleBarLocation(
            BubbleBarLocation.RIGHT,
            BubbleBarLocation.UpdateSource.DRAG_BUBBLE,
        )

        // 2 events: add bubble + drag event
        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(2)
        assertThat(uiEventLoggerFake.eventId(1))
            .isEqualTo(BubbleLogger.Event.BUBBLE_BAR_MOVED_RIGHT_DRAG_BUBBLE.id)
    }

    private fun addBubble(): Bubble {
        val bubble = FakeBubbleFactory.createChatBubble(context)
        bubble.setInflateSynchronously(true)
        bubbleData.notificationEntryUpdated(
            bubble,
            /* suppressFlyout= */ true,
            /* showInShade= */ true,
        )
        return bubble
    }

    private fun createBubbleController(
        shellInit: ShellInit,
        bubbleData: BubbleData,
        bubbleLogger: BubbleLogger,
        bubblePositioner: BubblePositioner,
        mainExecutor: TestShellExecutor,
        bgExecutor: TestShellExecutor,
    ): BubbleController {
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

        val shellTaskOrganizer = mock<ShellTaskOrganizer>()
        whenever(shellTaskOrganizer.executor).thenReturn(directExecutor())

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
            mock<WindowManager>(),
            WindowManagerShellWrapper(mainExecutor),
            mock<UserManager>(),
            mock<LauncherApps>(),
            bubbleLogger,
            mock<TaskStackListenerImpl>(),
            shellTaskOrganizer,
            bubblePositioner,
            mock<DisplayController>(),
            /* oneHandedOptional= */ Optional.empty(),
            mock<DragAndDropController>(),
            mainExecutor,
            mock<Handler>(),
            bgExecutor,
            mock<TaskViewTransitions>(),
            mock<Transitions>(),
            SyncTransactionQueue(TransactionPool(), mainExecutor),
            mock<IWindowManager>(),
            ProdBubbleProperties,
        )
    }

    private class FakeBubblesStateListener : Bubbles.BubbleStateListener {
        override fun onBubbleStateChange(update: BubbleBarUpdate?) {}

        override fun animateBubbleBarLocation(location: BubbleBarLocation?) {}
    }
}

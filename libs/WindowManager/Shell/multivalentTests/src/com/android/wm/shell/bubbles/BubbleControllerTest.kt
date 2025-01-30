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
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import android.view.IWindowManager
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.protolog.ProtoLog
import com.android.internal.statusbar.IStatusBarService
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.bubbles.Bubbles.SysuiProxy
import com.android.wm.shell.bubbles.storage.BubblePersistentRepository
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayImeController
import com.android.wm.shell.common.DisplayInsetsController
import com.android.wm.shell.common.FloatingContentCoordinator
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.common.TaskStackListenerImpl
import com.android.wm.shell.common.TestShellExecutor
import com.android.wm.shell.common.TestSyncExecutor
import com.android.wm.shell.draganddrop.DragAndDropController
import com.android.wm.shell.recents.RecentTasksController
import com.android.wm.shell.shared.TransactionPool
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.taskview.TaskViewRepository
import com.android.wm.shell.taskview.TaskViewTransitions
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.unfold.UnfoldAnimationController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Optional

/** Tests for [BubbleControllerTest] */
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
        val realWindowManager = context.getSystemService(WindowManager::class.java)
        // But we do want the metrics from the real one
        whenever(windowManager.currentWindowMetrics)
            .thenReturn(realWindowManager.currentWindowMetrics)

        bubblePositioner = BubblePositioner(context, windowManager)
        bubblePositioner.setShowingInBubbleBar(true)

        bubbleData = BubbleData(
            context, bubbleLogger, bubblePositioner, eduController,
            mainExecutor, bgExecutor
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
        bubbleController.asBubbles().setSysuiProxy(Mockito.mock(SysuiProxy::class.java))
        // Flush so that proxy gets set
        mainExecutor.flushAll()
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
        assertThat(bubbleData.getAnyBubbleWithKey(expectedKey)!!.isNoteBubble).isTrue()
    }


    fun createBubbleController(
        bubbleData: BubbleData,
        windowManager: WindowManager?,
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

        val shellTaskOrganizer = ShellTaskOrganizer(
            Mockito.mock<ShellInit>(ShellInit::class.java),
            ShellCommandHandler(),
            null,
            Optional.empty<UnfoldAnimationController>(),
            Optional.empty<RecentTasksController>(),
            TestSyncExecutor()
        )

        val resizeChecker: ResizabilityChecker =
            object : ResizabilityChecker {
                override fun isResizableActivity(
                    intent: Intent?,
                    packageManager: PackageManager, key: String
                ): Boolean {
                    return true
                }
            }

        val bubbleController = BubbleController(
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
            mock<DisplayInsetsController>(),
            mock<DisplayImeController>(),
            mock<UserManager>(),
            mock<LauncherApps>(),
            bubbleLogger,
            mock<TaskStackListenerImpl>(),
            shellTaskOrganizer,
            bubblePositioner,
            mock<DisplayController>(),
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
}
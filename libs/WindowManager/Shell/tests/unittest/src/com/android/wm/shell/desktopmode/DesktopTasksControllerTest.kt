/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.desktopmode

import android.app.ActivityManager.RecentTaskInfo
import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ActivityInfo.CONFIG_DENSITY
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.Binder
import android.os.Handler
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import android.view.Display.DEFAULT_DISPLAY
import android.view.DragEvent
import android.view.Gravity
import android.view.SurfaceControl
import android.view.WindowManager
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_BACK
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.DisplayAreaInfo
import android.window.IWindowContainerToken
import android.window.RemoteTransition
import android.window.TransitionRequestInfo
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_LAUNCH_TASK
import android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_PENDING_INTENT
import android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_TASK
import android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER
import android.window.WindowContainerTransaction.HierarchyOp.LAUNCH_KEY_TASK_ID
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.dx.mockito.inline.extended.ExtendedMockito.never
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.internal.jank.InteractionJankMonitor
import com.android.window.flags.Flags
import com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE
import com.android.wm.shell.MockToken
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.LaunchAdjacentController
import com.android.wm.shell.common.MultiInstanceHelper
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.desktopmode.DesktopTasksController.SnapPosition
import com.android.wm.shell.desktopmode.DesktopTasksController.TaskbarDesktopTaskListener
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createFreeformTask
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createFullscreenTask
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createHomeTask
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createSplitScreenTask
import com.android.wm.shell.desktopmode.persistence.Desktop
import com.android.wm.shell.desktopmode.persistence.DesktopPersistentRepository
import com.android.wm.shell.draganddrop.DragAndDropController
import com.android.wm.shell.recents.RecentTasksController
import com.android.wm.shell.recents.RecentsTransitionHandler
import com.android.wm.shell.recents.RecentsTransitionStateListener
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.shared.desktopmode.DesktopModeTransitionSource.UNKNOWN
import com.android.wm.shell.shared.split.SplitScreenConstants
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.OneShotRemoteHandler
import com.android.wm.shell.transition.TestRemoteTransition
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.ENABLE_SHELL_TRANSITIONS
import com.android.wm.shell.transition.Transitions.TransitionHandler
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.function.Consumer
import java.util.Optional
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.isA
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.capture
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

/**
 * Test class for {@link DesktopTasksController}
 *
 * Usage: atest WMShellUnitTests:DesktopTasksControllerTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@ExperimentalCoroutinesApi
@EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
class DesktopTasksControllerTest : ShellTestCase() {

  @JvmField @Rule val setFlagsRule = SetFlagsRule()

  @Mock lateinit var testExecutor: ShellExecutor
  @Mock lateinit var shellCommandHandler: ShellCommandHandler
  @Mock lateinit var shellController: ShellController
  @Mock lateinit var displayController: DisplayController
  @Mock lateinit var displayLayout: DisplayLayout
  @Mock lateinit var shellTaskOrganizer: ShellTaskOrganizer
  @Mock lateinit var syncQueue: SyncTransactionQueue
  @Mock lateinit var rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer
  @Mock lateinit var transitions: Transitions
  @Mock lateinit var keyguardManager: KeyguardManager
  @Mock lateinit var mReturnToDragStartAnimator: ReturnToDragStartAnimator
  @Mock lateinit var exitDesktopTransitionHandler: ExitDesktopTaskTransitionHandler
  @Mock lateinit var enterDesktopTransitionHandler: EnterDesktopTaskTransitionHandler
  @Mock lateinit var dragAndDropTransitionHandler: DesktopModeDragAndDropTransitionHandler
  @Mock
  lateinit var toggleResizeDesktopTaskTransitionHandler: ToggleResizeDesktopTaskTransitionHandler
  @Mock lateinit var dragToDesktopTransitionHandler: DragToDesktopTransitionHandler
  @Mock lateinit var launchAdjacentController: LaunchAdjacentController
  @Mock lateinit var splitScreenController: SplitScreenController
  @Mock lateinit var recentsTransitionHandler: RecentsTransitionHandler
  @Mock lateinit var dragAndDropController: DragAndDropController
  @Mock lateinit var multiInstanceHelper: MultiInstanceHelper
  @Mock lateinit var desktopModeLoggerTransitionObserver: DesktopModeLoggerTransitionObserver
  @Mock lateinit var desktopModeVisualIndicator: DesktopModeVisualIndicator
  @Mock lateinit var recentTasksController: RecentTasksController
  @Mock
  private lateinit var mockInteractionJankMonitor: InteractionJankMonitor
  @Mock private lateinit var mockSurface: SurfaceControl
  @Mock private lateinit var taskbarDesktopTaskListener: TaskbarDesktopTaskListener
  @Mock private lateinit var mockHandler: Handler
  @Mock lateinit var persistentRepository: DesktopPersistentRepository

  private lateinit var mockitoSession: StaticMockitoSession
  private lateinit var controller: DesktopTasksController
  private lateinit var shellInit: ShellInit
  private lateinit var taskRepository: DesktopModeTaskRepository
  private lateinit var desktopTasksLimiter: DesktopTasksLimiter
  private lateinit var recentsTransitionStateListener: RecentsTransitionStateListener
  private lateinit var testScope: CoroutineScope

  private val shellExecutor = TestShellExecutor()

  // Mock running tasks are registered here so we can get the list from mock shell task organizer
  private val runningTasks = mutableListOf<RunningTaskInfo>()

  private val DISPLAY_DIMENSION_SHORT = 1600
  private val DISPLAY_DIMENSION_LONG = 2560
  private val DEFAULT_LANDSCAPE_BOUNDS = Rect(320, 75, 2240, 1275)
  private val DEFAULT_PORTRAIT_BOUNDS = Rect(200, 165, 1400, 2085)
  private val RESIZABLE_LANDSCAPE_BOUNDS = Rect(25, 435, 1575, 1635)
  private val RESIZABLE_PORTRAIT_BOUNDS = Rect(680, 75, 1880, 1275)
  private val UNRESIZABLE_LANDSCAPE_BOUNDS = Rect(25, 449, 1575, 1611)
  private val UNRESIZABLE_PORTRAIT_BOUNDS = Rect(830, 75, 1730, 1275)

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
    mockitoSession =
        mockitoSession()
            .strictness(Strictness.LENIENT)
            .spyStatic(DesktopModeStatus::class.java)
            .startMocking()
    doReturn(true).`when` { DesktopModeStatus.isDesktopModeSupported(any()) }

    testScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    shellInit = spy(ShellInit(testExecutor))
    taskRepository = DesktopModeTaskRepository(context, shellInit, persistentRepository, testScope)
    desktopTasksLimiter =
        DesktopTasksLimiter(
            transitions,
            taskRepository,
            shellTaskOrganizer,
            MAX_TASK_LIMIT,
            mockInteractionJankMonitor,
            mContext,
            mockHandler)

    whenever(shellTaskOrganizer.getRunningTasks(anyInt())).thenAnswer { runningTasks }
    whenever(transitions.startTransition(anyInt(), any(), isNull())).thenAnswer { Binder() }
    whenever(enterDesktopTransitionHandler.moveToDesktop(any(), any())).thenAnswer { Binder() }
    whenever(displayController.getDisplayLayout(anyInt())).thenReturn(displayLayout)
    whenever(displayLayout.getStableBounds(any())).thenAnswer { i ->
      (i.arguments.first() as Rect).set(STABLE_BOUNDS)
    }
    whenever(runBlocking { persistentRepository.readDesktop(any(), any()) }).thenReturn(
      Desktop.getDefaultInstance()
    )

    val tda = DisplayAreaInfo(MockToken().token(), DEFAULT_DISPLAY, 0)
    tda.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN
    whenever(rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY)).thenReturn(tda)

    controller = createController()
    controller.setSplitScreenController(splitScreenController)

    shellInit.init()

    val captor = ArgumentCaptor.forClass(RecentsTransitionStateListener::class.java)
    verify(recentsTransitionHandler).addTransitionStateListener(captor.capture())
    recentsTransitionStateListener = captor.value

    controller.taskbarDesktopTaskListener = taskbarDesktopTaskListener
  }

  private fun createController(): DesktopTasksController {
    return DesktopTasksController(
        context,
        shellInit,
        shellCommandHandler,
        shellController,
        displayController,
        shellTaskOrganizer,
        syncQueue,
        rootTaskDisplayAreaOrganizer,
        dragAndDropController,
        transitions,
        keyguardManager,
        mReturnToDragStartAnimator,
        enterDesktopTransitionHandler,
        exitDesktopTransitionHandler,
        dragAndDropTransitionHandler,
        toggleResizeDesktopTaskTransitionHandler,
        dragToDesktopTransitionHandler,
        taskRepository,
        desktopModeLoggerTransitionObserver,
        launchAdjacentController,
        recentsTransitionHandler,
        multiInstanceHelper,
        shellExecutor,
        Optional.of(desktopTasksLimiter),
        recentTasksController,
        mockInteractionJankMonitor,
        mockHandler,
      )
  }

  @After
  fun tearDown() {
    mockitoSession.finishMocking()

    runningTasks.clear()
    testScope.cancel()
  }

  @Test
  fun instantiate_addInitCallback() {
    verify(shellInit).addInitCallback(any(), any<DesktopTasksController>())
  }

  @Test
  fun doesAnyTaskRequireTaskbarRounding_onlyFreeFormTaskIsRunning_returnFalse() {
    setUpFreeformTask()

    assertThat(controller.doesAnyTaskRequireTaskbarRounding(DEFAULT_DISPLAY)).isFalse()
  }

  @Test
  fun doesAnyTaskRequireTaskbarRounding_toggleResizeOfFreeFormTask_returnTrue() {
    val task1 = setUpFreeformTask()

    val argumentCaptor = ArgumentCaptor.forClass(Boolean::class.java)
    controller.toggleDesktopTaskSize(task1)
    verify(taskbarDesktopTaskListener).onTaskbarCornerRoundingUpdate(argumentCaptor.capture())

    assertThat(argumentCaptor.value).isTrue()
  }

  @Test
  fun doesAnyTaskRequireTaskbarRounding_fullScreenTaskIsRunning_returnTrue() {
    val stableBounds = Rect().apply { displayLayout.getStableBounds(this) }
    setUpFreeformTask(bounds = stableBounds, active = true)
    assertThat(controller.doesAnyTaskRequireTaskbarRounding(DEFAULT_DISPLAY)).isTrue()
  }

  @Test
  fun doesAnyTaskRequireTaskbarRounding_toggleResizeOfFullScreenTask_returnFalse() {
    val stableBounds = Rect().apply { displayLayout.getStableBounds(this) }
    val task1 = setUpFreeformTask(bounds = stableBounds, active = true)

    val argumentCaptor = ArgumentCaptor.forClass(Boolean::class.java)
    controller.toggleDesktopTaskSize(task1)
    verify(taskbarDesktopTaskListener).onTaskbarCornerRoundingUpdate(argumentCaptor.capture())

    assertThat(argumentCaptor.value).isFalse()
  }

  @Test
  fun doesAnyTaskRequireTaskbarRounding_splitScreenTaskIsRunning_returnTrue() {
    val stableBounds = Rect().apply { displayLayout.getStableBounds(this) }
    setUpFreeformTask(bounds = Rect(stableBounds.left, stableBounds.top, 500, stableBounds.bottom))

    assertThat(controller.doesAnyTaskRequireTaskbarRounding(DEFAULT_DISPLAY)).isTrue()
  }


  @Test
  fun instantiate_cannotEnterDesktopMode_doNotAddInitCallback() {
    whenever(DesktopModeStatus.canEnterDesktopMode(context)).thenReturn(false)
    clearInvocations(shellInit)

    createController()

    verify(shellInit, never()).addInitCallback(any(), any<DesktopTasksController>())
  }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_allAppsInvisible_bringsToFront_desktopWallpaperDisabled() {
    val homeTask = setUpHomeTask()
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    markTaskHidden(task1)
    markTaskHidden(task2)

    controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    assertThat(wct.hierarchyOps).hasSize(3)
    // Expect order to be from bottom: home, task1, task2
    wct.assertReorderAt(index = 0, homeTask)
    wct.assertReorderAt(index = 1, task1)
    wct.assertReorderAt(index = 2, task2)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_allAppsInvisible_bringsToFront_desktopWallpaperEnabled() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    markTaskHidden(task1)
    markTaskHidden(task2)

    controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    assertThat(wct.hierarchyOps).hasSize(3)
    // Expect order to be from bottom: wallpaper intent, task1, task2
    wct.assertPendingIntentAt(index = 0, desktopWallpaperIntent)
    wct.assertReorderAt(index = 1, task1)
    wct.assertReorderAt(index = 2, task2)
  }

  @Test
  fun isDesktopModeShowing_noTasks_returnsFalse() {
    assertThat(controller.isDesktopModeShowing(displayId = 0)).isFalse()
  }

  @Test
  fun isDesktopModeShowing_noTasksVisible_returnsFalse() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    markTaskHidden(task1)
    markTaskHidden(task2)

    assertThat(controller.isDesktopModeShowing(displayId = 0)).isFalse()
  }

  @Test
  fun isDesktopModeShowing_tasksActiveAndVisible_returnsTrue() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    markTaskVisible(task1)
    markTaskHidden(task2)

    assertThat(controller.isDesktopModeShowing(displayId = 0)).isTrue()
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_onSecondaryDisplay_desktopWallpaperEnabled_shouldNotShowWallpaper() {
    val homeTask = setUpHomeTask(SECOND_DISPLAY)
    val task1 = setUpFreeformTask(SECOND_DISPLAY)
    val task2 = setUpFreeformTask(SECOND_DISPLAY)
    markTaskHidden(task1)
    markTaskHidden(task2)

    controller.showDesktopApps(SECOND_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    assertThat(wct.hierarchyOps).hasSize(3)
    // Expect order to be from bottom: home, task1, task2 (no wallpaper intent)
    wct.assertReorderAt(index = 0, homeTask)
    wct.assertReorderAt(index = 1, task1)
    wct.assertReorderAt(index = 2, task2)
  }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_appsAlreadyVisible_bringsToFront_desktopWallpaperDisabled() {
    val homeTask = setUpHomeTask()
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    markTaskVisible(task1)
    markTaskVisible(task2)

    controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    assertThat(wct.hierarchyOps).hasSize(3)
    // Expect order to be from bottom: home, task1, task2
    wct.assertReorderAt(index = 0, homeTask)
    wct.assertReorderAt(index = 1, task1)
    wct.assertReorderAt(index = 2, task2)
  }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_onSecondaryDisplay_desktopWallpaperDisabled_shouldNotMoveLauncher() {
    val homeTask = setUpHomeTask(SECOND_DISPLAY)
    val task1 = setUpFreeformTask(SECOND_DISPLAY)
    val task2 = setUpFreeformTask(SECOND_DISPLAY)
    markTaskHidden(task1)
    markTaskHidden(task2)

    controller.showDesktopApps(SECOND_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    assertThat(wct.hierarchyOps).hasSize(3)
    // Expect order to be from bottom: home, task1, task2
    wct.assertReorderAt(index = 0, homeTask)
    wct.assertReorderAt(index = 1, task1)
    wct.assertReorderAt(index = 2, task2)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_appsAlreadyVisible_bringsToFront_desktopWallpaperEnabled() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    markTaskVisible(task1)
    markTaskVisible(task2)

    controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    assertThat(wct.hierarchyOps).hasSize(3)
    // Expect order to be from bottom: wallpaper intent, task1, task2
    wct.assertPendingIntentAt(index = 0, desktopWallpaperIntent)
    wct.assertReorderAt(index = 1, task1)
    wct.assertReorderAt(index = 2, task2)
  }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_someAppsInvisible_reordersAll_desktopWallpaperDisabled() {
    val homeTask = setUpHomeTask()
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    markTaskHidden(task1)
    markTaskVisible(task2)

    controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    assertThat(wct.hierarchyOps).hasSize(3)
    // Expect order to be from bottom: home, task1, task2
    wct.assertReorderAt(index = 0, homeTask)
    wct.assertReorderAt(index = 1, task1)
    wct.assertReorderAt(index = 2, task2)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_someAppsInvisible_reordersAll_desktopWallpaperEnabled() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    markTaskHidden(task1)
    markTaskVisible(task2)

    controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    assertThat(wct.hierarchyOps).hasSize(3)
    // Expect order to be from bottom: wallpaper intent, task1, task2
    wct.assertPendingIntentAt(index = 0, desktopWallpaperIntent)
    wct.assertReorderAt(index = 1, task1)
    wct.assertReorderAt(index = 2, task2)
  }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_noActiveTasks_reorderHomeToTop_desktopWallpaperDisabled() {
    val homeTask = setUpHomeTask()

    controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    assertThat(wct.hierarchyOps).hasSize(1)
    wct.assertReorderAt(index = 0, homeTask)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_noActiveTasks_addDesktopWallpaper_desktopWallpaperEnabled() {
    controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    wct.assertPendingIntentAt(index = 0, desktopWallpaperIntent)
  }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_twoDisplays_bringsToFrontOnlyOneDisplay_desktopWallpaperDisabled() {
    val homeTaskDefaultDisplay = setUpHomeTask(DEFAULT_DISPLAY)
    val taskDefaultDisplay = setUpFreeformTask(DEFAULT_DISPLAY)
    setUpHomeTask(SECOND_DISPLAY)
    val taskSecondDisplay = setUpFreeformTask(SECOND_DISPLAY)
    markTaskHidden(taskDefaultDisplay)
    markTaskHidden(taskSecondDisplay)

    controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    assertThat(wct.hierarchyOps).hasSize(2)
    // Expect order to be from bottom: home, task
    wct.assertReorderAt(index = 0, homeTaskDefaultDisplay)
    wct.assertReorderAt(index = 1, taskDefaultDisplay)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_twoDisplays_bringsToFrontOnlyOneDisplay_desktopWallpaperEnabled() {
    val homeTaskDefaultDisplay = setUpHomeTask(DEFAULT_DISPLAY)
    val taskDefaultDisplay = setUpFreeformTask(DEFAULT_DISPLAY)
    setUpHomeTask(SECOND_DISPLAY)
    val taskSecondDisplay = setUpFreeformTask(SECOND_DISPLAY)
    markTaskHidden(taskDefaultDisplay)
    markTaskHidden(taskSecondDisplay)

    controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    assertThat(wct.hierarchyOps).hasSize(3)
    // Move home to front
    wct.assertReorderAt(index = 0, homeTaskDefaultDisplay)
    // Add desktop wallpaper activity
    wct.assertPendingIntentAt(index = 1, desktopWallpaperIntent)
    // Move freeform task to front
    wct.assertReorderAt(index = 2, taskDefaultDisplay)
  }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_desktopWallpaperDisabled_dontReorderMinimizedTask() {
    val homeTask = setUpHomeTask()
    val freeformTask = setUpFreeformTask()
    val minimizedTask = setUpFreeformTask()

    markTaskHidden(freeformTask)
    markTaskHidden(minimizedTask)
    taskRepository.minimizeTask(DEFAULT_DISPLAY, minimizedTask.taskId)
    controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    assertThat(wct.hierarchyOps).hasSize(2)
    // Reorder home and freeform task to top, don't reorder the minimized task
    wct.assertReorderAt(index = 0, homeTask, toTop = true)
    wct.assertReorderAt(index = 1, freeformTask, toTop = true)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun showDesktopApps_desktopWallpaperEnabled_dontReorderMinimizedTask() {
    val homeTask = setUpHomeTask()
    val freeformTask = setUpFreeformTask()
    val minimizedTask = setUpFreeformTask()

    markTaskHidden(freeformTask)
    markTaskHidden(minimizedTask)
    taskRepository.minimizeTask(DEFAULT_DISPLAY, minimizedTask.taskId)
    controller.showDesktopApps(DEFAULT_DISPLAY, RemoteTransition(TestRemoteTransition()))

    val wct = getLatestWct(type = TRANSIT_TO_FRONT, handlerClass = OneShotRemoteHandler::class.java)
    assertThat(wct.hierarchyOps).hasSize(3)
    // Move home to front
    wct.assertReorderAt(index = 0, homeTask, toTop = true)
    // Add desktop wallpaper activity
    wct.assertPendingIntentAt(index = 1, desktopWallpaperIntent)
    // Reorder freeform task to top, don't reorder the minimized task
    wct.assertReorderAt(index = 2, freeformTask, toTop = true)
  }

  @Test
  fun visibleTaskCount_noTasks_returnsZero() {
    assertThat(controller.visibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(0)
  }

  @Test
  fun visibleTaskCount_twoTasks_bothVisible_returnsTwo() {
    setUpHomeTask()
    setUpFreeformTask().also(::markTaskVisible)
    setUpFreeformTask().also(::markTaskVisible)
    assertThat(controller.visibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(2)
  }

  @Test
  fun visibleTaskCount_twoTasks_oneVisible_returnsOne() {
    setUpHomeTask()
    setUpFreeformTask().also(::markTaskVisible)
    setUpFreeformTask().also(::markTaskHidden)
    assertThat(controller.visibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(1)
  }

  @Test
  fun visibleTaskCount_twoTasksVisibleOnDifferentDisplays_returnsOne() {
    setUpHomeTask()
    setUpFreeformTask(DEFAULT_DISPLAY).also(::markTaskVisible)
    setUpFreeformTask(SECOND_DISPLAY).also(::markTaskVisible)
    assertThat(controller.visibleTaskCount(SECOND_DISPLAY)).isEqualTo(1)
  }

  @Test
  fun addMoveToDesktopChanges_gravityLeft_noBoundsApplied() {
    setUpLandscapeDisplay()
    val task = setUpFullscreenTask(gravity = Gravity.LEFT)
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    val finalBounds = findBoundsChange(wct, task)
    assertThat(finalBounds).isEqualTo(Rect())
  }

  @Test
  fun addMoveToDesktopChanges_gravityRight_noBoundsApplied() {
    setUpLandscapeDisplay()
    val task = setUpFullscreenTask(gravity = Gravity.RIGHT)
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    val finalBounds = findBoundsChange(wct, task)
    assertThat(finalBounds).isEqualTo(Rect())
  }

  @Test
  fun addMoveToDesktopChanges_gravityTop_noBoundsApplied() {
    setUpLandscapeDisplay()
    val task = setUpFullscreenTask(gravity = Gravity.TOP)
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    val finalBounds = findBoundsChange(wct, task)
    assertThat(finalBounds).isEqualTo(Rect())
  }

  @Test
  fun addMoveToDesktopChanges_gravityBottom_noBoundsApplied() {
    setUpLandscapeDisplay()
    val task = setUpFullscreenTask(gravity = Gravity.BOTTOM)
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    val finalBounds = findBoundsChange(wct, task)
    assertThat(finalBounds).isEqualTo(Rect())
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_CASCADING_WINDOWS)
  fun handleRequest_newFreeformTaskLaunch_cascadeApplied() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)
    setUpLandscapeDisplay()
    val stableBounds = Rect()
    displayLayout.getStableBoundsForDesktopMode(stableBounds)

    setUpFreeformTask(bounds = DEFAULT_LANDSCAPE_BOUNDS)
    val freeformTask = setUpFreeformTask(bounds = DEFAULT_LANDSCAPE_BOUNDS, active = false)

    val wct = controller.handleRequest(Binder(), createTransition(freeformTask))

    assertNotNull(wct, "should handle request")
    val finalBounds = findBoundsChange(wct, freeformTask)
    assertThat(stableBounds.getDesktopTaskPosition(finalBounds!!))
      .isEqualTo(DesktopTaskPosition.BottomRight)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_CASCADING_WINDOWS)
  fun handleRequest_freeformTaskAlreadyExistsInDesktopMode_cascadeNotApplied() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)
    setUpLandscapeDisplay()
    val stableBounds = Rect()
    displayLayout.getStableBoundsForDesktopMode(stableBounds)

    setUpFreeformTask(bounds = DEFAULT_LANDSCAPE_BOUNDS)
    val freeformTask = setUpFreeformTask(bounds = DEFAULT_LANDSCAPE_BOUNDS)

    val wct = controller.handleRequest(Binder(), createTransition(freeformTask))

    assertNull(wct, "should not handle request")
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_CASCADING_WINDOWS)
  fun addMoveToDesktopChanges_positionBottomRight() {
    setUpLandscapeDisplay()
    val stableBounds = Rect()
    displayLayout.getStableBoundsForDesktopMode(stableBounds)

    setUpFreeformTask(bounds = DEFAULT_LANDSCAPE_BOUNDS)

    val task = setUpFullscreenTask()
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    val finalBounds = findBoundsChange(wct, task)
    assertThat(stableBounds.getDesktopTaskPosition(finalBounds!!))
      .isEqualTo(DesktopTaskPosition.BottomRight)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_CASCADING_WINDOWS)
  fun addMoveToDesktopChanges_positionTopLeft() {
    setUpLandscapeDisplay()
    val stableBounds = Rect()
    displayLayout.getStableBoundsForDesktopMode(stableBounds)

    addFreeformTaskAtPosition(DesktopTaskPosition.BottomRight, stableBounds)

    val task = setUpFullscreenTask()
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    val finalBounds = findBoundsChange(wct, task)
    assertThat(stableBounds.getDesktopTaskPosition(finalBounds!!))
      .isEqualTo(DesktopTaskPosition.TopLeft)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_CASCADING_WINDOWS)
  fun addMoveToDesktopChanges_positionBottomLeft() {
    setUpLandscapeDisplay()
    val stableBounds = Rect()
    displayLayout.getStableBoundsForDesktopMode(stableBounds)

    addFreeformTaskAtPosition(DesktopTaskPosition.TopLeft, stableBounds)

    val task = setUpFullscreenTask()
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    val finalBounds = findBoundsChange(wct, task)
    assertThat(stableBounds.getDesktopTaskPosition(finalBounds!!))
      .isEqualTo(DesktopTaskPosition.BottomLeft)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_CASCADING_WINDOWS)
  fun addMoveToDesktopChanges_positionTopRight() {
    setUpLandscapeDisplay()
    val stableBounds = Rect()
    displayLayout.getStableBoundsForDesktopMode(stableBounds)

    addFreeformTaskAtPosition(DesktopTaskPosition.BottomLeft, stableBounds)

    val task = setUpFullscreenTask()
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    val finalBounds = findBoundsChange(wct, task)
    assertThat(stableBounds.getDesktopTaskPosition(finalBounds!!))
      .isEqualTo(DesktopTaskPosition.TopRight)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_CASCADING_WINDOWS)
  fun addMoveToDesktopChanges_positionResetsToCenter() {
    setUpLandscapeDisplay()
    val stableBounds = Rect()
    displayLayout.getStableBoundsForDesktopMode(stableBounds)

    addFreeformTaskAtPosition(DesktopTaskPosition.TopRight, stableBounds)

    val task = setUpFullscreenTask()
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    val finalBounds = findBoundsChange(wct, task)
    assertThat(stableBounds.getDesktopTaskPosition(finalBounds!!))
      .isEqualTo(DesktopTaskPosition.Center)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_CASCADING_WINDOWS)
  fun addMoveToDesktopChanges_lastWindowSnapLeft_positionResetsToCenter() {
    setUpLandscapeDisplay()
    val stableBounds = Rect()
    displayLayout.getStableBoundsForDesktopMode(stableBounds)

    // Add freeform task with half display size snap bounds at left side.
    setUpFreeformTask(bounds = Rect(stableBounds.left, stableBounds.top, 500, stableBounds.bottom))

    val task = setUpFullscreenTask()
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    val finalBounds = findBoundsChange(wct, task)
    assertThat(stableBounds.getDesktopTaskPosition(finalBounds!!))
      .isEqualTo(DesktopTaskPosition.Center)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_CASCADING_WINDOWS)
  fun addMoveToDesktopChanges_lastWindowSnapRight_positionResetsToCenter() {
    setUpLandscapeDisplay()
    val stableBounds = Rect()
    displayLayout.getStableBoundsForDesktopMode(stableBounds)

    // Add freeform task with half display size snap bounds at right side.
    setUpFreeformTask(bounds = Rect(
      stableBounds.right - 500, stableBounds.top, stableBounds.right, stableBounds.bottom))

    val task = setUpFullscreenTask()
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    val finalBounds = findBoundsChange(wct, task)
    assertThat(stableBounds.getDesktopTaskPosition(finalBounds!!))
      .isEqualTo(DesktopTaskPosition.Center)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_CASCADING_WINDOWS)
  fun addMoveToDesktopChanges_lastWindowMaximised_positionResetsToCenter() {
    setUpLandscapeDisplay()
    val stableBounds = Rect()
    displayLayout.getStableBoundsForDesktopMode(stableBounds)

    // Add maximised freeform task.
    setUpFreeformTask(bounds = Rect(stableBounds))

    val task = setUpFullscreenTask()
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    val finalBounds = findBoundsChange(wct, task)
    assertThat(stableBounds.getDesktopTaskPosition(finalBounds!!))
      .isEqualTo(DesktopTaskPosition.Center)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_CASCADING_WINDOWS)
  fun addMoveToDesktopChanges_defaultToCenterIfFree() {
    setUpLandscapeDisplay()
    val stableBounds = Rect()
    displayLayout.getStableBoundsForDesktopMode(stableBounds)

    val minTouchTarget = context.resources.getDimensionPixelSize(
      R.dimen.freeform_required_visible_empty_space_in_header)
    addFreeformTaskAtPosition(DesktopTaskPosition.Center, stableBounds,
      Rect(0, 0, 1600, 1200), Point(0, minTouchTarget + 1))

    val task = setUpFullscreenTask()
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    val finalBounds = findBoundsChange(wct, task)
    assertThat(stableBounds.getDesktopTaskPosition(finalBounds!!))
      .isEqualTo(DesktopTaskPosition.Center)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun addMoveToDesktopChanges_landscapeDevice_userFullscreenOverride_defaultPortraitBounds() {
    setUpLandscapeDisplay()
    val task = setUpFullscreenTask(enableUserFullscreenOverride = true)
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    assertThat(findBoundsChange(wct, task)).isEqualTo(DEFAULT_LANDSCAPE_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun addMoveToDesktopChanges_landscapeDevice_systemFullscreenOverride_defaultPortraitBounds() {
    setUpLandscapeDisplay()
    val task = setUpFullscreenTask(enableSystemFullscreenOverride = true)
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    assertThat(findBoundsChange(wct, task)).isEqualTo(DEFAULT_LANDSCAPE_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun addMoveToDesktopChanges_landscapeDevice_portraitResizableApp_aspectRatioOverridden() {
    setUpLandscapeDisplay()
    val task = setUpFullscreenTask(screenOrientation = SCREEN_ORIENTATION_PORTRAIT,
      shouldLetterbox = true, aspectRatioOverrideApplied = true)
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    assertThat(findBoundsChange(wct, task)).isEqualTo(UNRESIZABLE_PORTRAIT_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun addMoveToDesktopChanges_portraitDevice_userFullscreenOverride_defaultPortraitBounds() {
    setUpPortraitDisplay()
    val task = setUpFullscreenTask(enableUserFullscreenOverride = true)
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    assertThat(findBoundsChange(wct, task)).isEqualTo(DEFAULT_PORTRAIT_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun addMoveToDesktopChanges_portraitDevice_systemFullscreenOverride_defaultPortraitBounds() {
    setUpPortraitDisplay()
    val task = setUpFullscreenTask(enableSystemFullscreenOverride = true)
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    assertThat(findBoundsChange(wct, task)).isEqualTo(DEFAULT_PORTRAIT_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun addMoveToDesktopChanges_portraitDevice_landscapeResizableApp_aspectRatioOverridden() {
    setUpPortraitDisplay()
    val task = setUpFullscreenTask(screenOrientation = SCREEN_ORIENTATION_LANDSCAPE,
      deviceOrientation = ORIENTATION_PORTRAIT,
      shouldLetterbox = true, aspectRatioOverrideApplied = true)
    val wct = WindowContainerTransaction()
    controller.addMoveToDesktopChanges(wct, task)

    assertThat(findBoundsChange(wct, task)).isEqualTo(UNRESIZABLE_LANDSCAPE_BOUNDS)
  }

  @Test
  fun moveToDesktop_tdaFullscreen_windowingModeSetToFreeform() {
    val task = setUpFullscreenTask()
    val tda = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY)!!
    tda.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN
    controller.moveRunningTaskToDesktop(task, transitionSource = UNKNOWN)
    val wct = getLatestEnterDesktopWct()
    assertThat(wct.changes[task.token.asBinder()]?.windowingMode).isEqualTo(WINDOWING_MODE_FREEFORM)
  }

  @Test
  fun moveRunningTaskToDesktop_tdaFreeform_windowingModeSetToUndefined() {
    val task = setUpFullscreenTask()
    val tda = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY)!!
    tda.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FREEFORM
    controller.moveRunningTaskToDesktop(task, transitionSource = UNKNOWN)
    val wct = getLatestEnterDesktopWct()
    assertThat(wct.changes[task.token.asBinder()]?.windowingMode)
        .isEqualTo(WINDOWING_MODE_UNDEFINED)
  }

  @Test
  fun moveTaskToDesktop_nonExistentTask_doesNothing() {
    controller.moveTaskToDesktop(999, transitionSource = UNKNOWN)
    verifyEnterDesktopWCTNotExecuted()
  }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun moveTaskToDesktop_desktopWallpaperDisabled_nonRunningTask_launchesInFreeform() {
    val task = createTaskInfo(1)
    whenever(shellTaskOrganizer.getRunningTaskInfo(anyInt())).thenReturn(null)
    whenever(recentTasksController.findTaskInBackground(anyInt())).thenReturn(task)

    controller.moveTaskToDesktop(task.taskId, transitionSource = UNKNOWN)

    with(getLatestEnterDesktopWct()) {
      assertLaunchTaskAt(0, task.taskId, WINDOWING_MODE_FREEFORM)
    }
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun moveTaskToDesktop_desktopWallpaperEnabled_nonRunningTask_launchesInFreeform() {
    val task = createTaskInfo(1)
    whenever(shellTaskOrganizer.getRunningTaskInfo(anyInt())).thenReturn(null)
    whenever(recentTasksController.findTaskInBackground(anyInt())).thenReturn(task)

    controller.moveTaskToDesktop(task.taskId, transitionSource = UNKNOWN)

    with(getLatestEnterDesktopWct()) {
      // Add desktop wallpaper activity
      assertPendingIntentAt(index = 0, desktopWallpaperIntent)
      // Launch task
      assertLaunchTaskAt(index = 1, task.taskId, WINDOWING_MODE_FREEFORM)
    }
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODALS_POLICY)
  fun moveRunningTaskToDesktop_topActivityTranslucentWithStyleFloating_taskIsMovedToDesktop() {
    val task =
      setUpFullscreenTask().apply {
        isTopActivityTransparent = true
        isTopActivityStyleFloating = true
        numActivities = 1
      }

    controller.moveRunningTaskToDesktop(task, transitionSource = UNKNOWN)

    val wct = getLatestEnterDesktopWct()
    assertThat(wct.changes[task.token.asBinder()]?.windowingMode).isEqualTo(WINDOWING_MODE_FREEFORM)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODALS_POLICY)
  fun moveRunningTaskToDesktop_topActivityTranslucentWithoutStyleFloating_doesNothing() {
    val task =
      setUpFullscreenTask().apply {
        isTopActivityTransparent = true
        isTopActivityStyleFloating = false
        numActivities = 1
      }

    controller.moveRunningTaskToDesktop(task, transitionSource = UNKNOWN)
    verifyEnterDesktopWCTNotExecuted()
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODALS_POLICY)
  fun moveRunningTaskToDesktop_systemUIActivity_doesNothing() {
    val task = setUpFullscreenTask()

    // Set task as systemUI package
    val systemUIPackageName = context.resources.getString(
      com.android.internal.R.string.config_systemUi)
    val baseComponent = ComponentName(systemUIPackageName, /* class */ "")
    task.baseActivity = baseComponent

    controller.moveRunningTaskToDesktop(task, transitionSource = UNKNOWN)
    verifyEnterDesktopWCTNotExecuted()
  }

  @Test
  fun moveRunningTaskToDesktop_deviceSupported_taskIsMovedToDesktop() {
    val task = setUpFullscreenTask()

    controller.moveRunningTaskToDesktop(task, transitionSource = UNKNOWN)

    val wct = getLatestEnterDesktopWct()
    assertThat(wct.changes[task.token.asBinder()]?.windowingMode).isEqualTo(WINDOWING_MODE_FREEFORM)
  }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun moveRunningTaskToDesktop_otherFreeformTasksBroughtToFront_desktopWallpaperDisabled() {
    val homeTask = setUpHomeTask()
    val freeformTask = setUpFreeformTask()
    val fullscreenTask = setUpFullscreenTask()
    markTaskHidden(freeformTask)

    controller.moveRunningTaskToDesktop(fullscreenTask, transitionSource = UNKNOWN)

    with(getLatestEnterDesktopWct()) {
      // Operations should include home task, freeform task
      assertThat(hierarchyOps).hasSize(3)
      assertReorderSequence(homeTask, freeformTask, fullscreenTask)
      assertThat(changes[fullscreenTask.token.asBinder()]?.windowingMode)
          .isEqualTo(WINDOWING_MODE_FREEFORM)
    }
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun moveRunningTaskToDesktop_otherFreeformTasksBroughtToFront_desktopWallpaperEnabled() {
    val freeformTask = setUpFreeformTask()
    val fullscreenTask = setUpFullscreenTask()
    markTaskHidden(freeformTask)

    controller.moveRunningTaskToDesktop(fullscreenTask, transitionSource = UNKNOWN)

    with(getLatestEnterDesktopWct()) {
      // Operations should include wallpaper intent, freeform task, fullscreen task
      assertThat(hierarchyOps).hasSize(3)
      assertPendingIntentAt(index = 0, desktopWallpaperIntent)
      assertReorderAt(index = 1, freeformTask)
      assertReorderAt(index = 2, fullscreenTask)
      assertThat(changes[fullscreenTask.token.asBinder()]?.windowingMode)
          .isEqualTo(WINDOWING_MODE_FREEFORM)
    }
  }

  @Test
  fun moveRunningTaskToDesktop_onlyFreeformTasksFromCurrentDisplayBroughtToFront() {
    setUpHomeTask(displayId = DEFAULT_DISPLAY)
    val freeformTaskDefault = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val fullscreenTaskDefault = setUpFullscreenTask(displayId = DEFAULT_DISPLAY)
    markTaskHidden(freeformTaskDefault)

    val homeTaskSecond = setUpHomeTask(displayId = SECOND_DISPLAY)
    val freeformTaskSecond = setUpFreeformTask(displayId = SECOND_DISPLAY)
    markTaskHidden(freeformTaskSecond)

    controller.moveRunningTaskToDesktop(fullscreenTaskDefault, transitionSource = UNKNOWN)

    with(getLatestEnterDesktopWct()) {
      // Check that hierarchy operations do not include tasks from second display
      assertThat(hierarchyOps.map { it.container }).doesNotContain(homeTaskSecond.token.asBinder())
      assertThat(hierarchyOps.map { it.container })
          .doesNotContain(freeformTaskSecond.token.asBinder())
    }
  }

  @Test
  fun moveRunningTaskToDesktop_splitTaskExitsSplit() {
    val task = setUpSplitScreenTask()
    controller.moveRunningTaskToDesktop(task, transitionSource = UNKNOWN)
    val wct = getLatestEnterDesktopWct()
    assertThat(wct.changes[task.token.asBinder()]?.windowingMode).isEqualTo(WINDOWING_MODE_FREEFORM)
    verify(splitScreenController)
        .prepareExitSplitScreen(any(), anyInt(), eq(SplitScreenController.EXIT_REASON_DESKTOP_MODE))
  }

  @Test
  fun moveRunningTaskToDesktop_fullscreenTaskDoesNotExitSplit() {
    val task = setUpFullscreenTask()
    controller.moveRunningTaskToDesktop(task, transitionSource = UNKNOWN)
    val wct = getLatestEnterDesktopWct()
    assertThat(wct.changes[task.token.asBinder()]?.windowingMode).isEqualTo(WINDOWING_MODE_FREEFORM)
    verify(splitScreenController, never())
        .prepareExitSplitScreen(any(), anyInt(), eq(SplitScreenController.EXIT_REASON_DESKTOP_MODE))
  }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun moveRunningTaskToDesktop_desktopWallpaperDisabled_bringsTasksOver_dontShowBackTask() {
    val freeformTasks = (1..MAX_TASK_LIMIT).map { _ -> setUpFreeformTask() }
    val newTask = setUpFullscreenTask()
    val homeTask = setUpHomeTask()

    controller.moveRunningTaskToDesktop(newTask, transitionSource = UNKNOWN)

    val wct = getLatestEnterDesktopWct()
    assertThat(wct.hierarchyOps.size).isEqualTo(MAX_TASK_LIMIT + 1) // visible tasks + home
    wct.assertReorderAt(0, homeTask)
    wct.assertReorderSequenceInRange(
        range = 1..<(MAX_TASK_LIMIT + 1),
        *freeformTasks.drop(1).toTypedArray(), // Skipping freeformTasks[0]
        newTask)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun moveRunningTaskToDesktop_desktopWallpaperEnabled_bringsTasksOverLimit_dontShowBackTask() {
    val freeformTasks = (1..MAX_TASK_LIMIT).map { _ -> setUpFreeformTask() }
    val newTask = setUpFullscreenTask()
    val homeTask = setUpHomeTask()

    controller.moveRunningTaskToDesktop(newTask, transitionSource = UNKNOWN)

    val wct = getLatestEnterDesktopWct()
    assertThat(wct.hierarchyOps.size).isEqualTo(MAX_TASK_LIMIT + 2) // tasks + home + wallpaper
    // Move home to front
    wct.assertReorderAt(0, homeTask)
    // Add desktop wallpaper activity
    wct.assertPendingIntentAt(1, desktopWallpaperIntent)
    // Bring freeform tasks to front
    wct.assertReorderSequenceInRange(
        range = 2..<(MAX_TASK_LIMIT + 2),
        *freeformTasks.drop(1).toTypedArray(), // Skipping freeformTasks[0]
        newTask)
  }

  @Test
  fun moveToFullscreen_tdaFullscreen_windowingModeSetToUndefined() {
    val task = setUpFreeformTask()
    val tda = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY)!!
    tda.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN
    controller.moveToFullscreen(task.taskId, transitionSource = UNKNOWN)
    val wct = getLatestExitDesktopWct()
    assertThat(wct.changes[task.token.asBinder()]?.windowingMode)
        .isEqualTo(WINDOWING_MODE_UNDEFINED)
  }

  @Test
  fun moveToFullscreen_tdaFullscreen_windowingModeUndefined_removesWallpaperActivity() {
    val task = setUpFreeformTask()
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    assertNotNull(rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY))
      .configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN

    controller.moveToFullscreen(task.taskId, transitionSource = UNKNOWN)

    val wct = getLatestExitDesktopWct()
    val taskChange = assertNotNull(wct.changes[task.token.asBinder()])
    assertThat(taskChange.windowingMode).isEqualTo(WINDOWING_MODE_UNDEFINED)
    // Removes wallpaper activity when leaving desktop
    wct.assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  fun moveToFullscreen_tdaFreeform_windowingModeSetToFullscreen() {
    val task = setUpFreeformTask()
    val tda = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY)!!
    tda.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FREEFORM
    controller.moveToFullscreen(task.taskId, transitionSource = UNKNOWN)
    val wct = getLatestExitDesktopWct()
    assertThat(wct.changes[task.token.asBinder()]?.windowingMode)
        .isEqualTo(WINDOWING_MODE_FULLSCREEN)
  }

  @Test
  fun moveToFullscreen_tdaFreeform_windowingModeFullscreen_removesWallpaperActivity() {
    val task = setUpFreeformTask()
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    assertNotNull(rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY))
      .configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FREEFORM

    controller.moveToFullscreen(task.taskId, transitionSource = UNKNOWN)

    val wct = getLatestExitDesktopWct()
    val taskChange = assertNotNull(wct.changes[task.token.asBinder()])
    assertThat(taskChange.windowingMode).isEqualTo(WINDOWING_MODE_FULLSCREEN)
    // Removes wallpaper activity when leaving desktop
    wct.assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  fun moveToFullscreen_multipleVisibleNonMinimizedTasks_doesNotRemoveWallpaperActivity() {
    val task1 = setUpFreeformTask()
    // Setup task2
    setUpFreeformTask()
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    assertNotNull(rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY))
      .configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN

    controller.moveToFullscreen(task1.taskId, transitionSource = UNKNOWN)

    val wct = getLatestExitDesktopWct()
    val task1Change = assertNotNull(wct.changes[task1.token.asBinder()])
    assertThat(task1Change.windowingMode).isEqualTo(WINDOWING_MODE_UNDEFINED)
    // Does not remove wallpaper activity, as desktop still has a visible desktop task
    assertThat(wct.hierarchyOps).isEmpty()
  }

  @Test
  fun moveToFullscreen_nonExistentTask_doesNothing() {
    controller.moveToFullscreen(999, transitionSource = UNKNOWN)
    verifyExitDesktopWCTNotExecuted()
  }

  @Test
  fun moveToFullscreen_secondDisplayTaskHasFreeform_secondDisplayNotAffected() {
    val taskDefaultDisplay = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val taskSecondDisplay = setUpFreeformTask(displayId = SECOND_DISPLAY)

    controller.moveToFullscreen(taskDefaultDisplay.taskId, transitionSource = UNKNOWN)

    with(getLatestExitDesktopWct()) {
      assertThat(changes.keys).contains(taskDefaultDisplay.token.asBinder())
      assertThat(changes.keys).doesNotContain(taskSecondDisplay.token.asBinder())
    }
  }

  @Test
  fun moveTaskToFront_postsWctWithReorderOp() {
    val task1 = setUpFreeformTask()
    setUpFreeformTask()

    controller.moveTaskToFront(task1)

    val wct = getLatestWct(type = TRANSIT_TO_FRONT)
    assertThat(wct.hierarchyOps).hasSize(1)
    wct.assertReorderAt(index = 0, task1)
  }

  @Test
  fun moveTaskToFront_bringsTasksOverLimit_minimizesBackTask() {
    setUpHomeTask()
    val freeformTasks = (1..MAX_TASK_LIMIT + 1).map { _ -> setUpFreeformTask() }

    controller.moveTaskToFront(freeformTasks[0])

    val wct = getLatestWct(type = TRANSIT_TO_FRONT)
    assertThat(wct.hierarchyOps.size).isEqualTo(2) // move-to-front + minimize
    wct.assertReorderAt(0, freeformTasks[0], toTop = true)
    wct.assertReorderAt(1, freeformTasks[1], toTop = false)
  }

  @Test
  fun moveToNextDisplay_noOtherDisplays() {
    whenever(rootTaskDisplayAreaOrganizer.displayIds).thenReturn(intArrayOf(DEFAULT_DISPLAY))
    val task = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    controller.moveToNextDisplay(task.taskId)
    verifyWCTNotExecuted()
  }

  @Test
  fun moveToNextDisplay_moveFromFirstToSecondDisplay() {
    // Set up two display ids
    whenever(rootTaskDisplayAreaOrganizer.displayIds)
        .thenReturn(intArrayOf(DEFAULT_DISPLAY, SECOND_DISPLAY))
    // Create a mock for the target display area: second display
    val secondDisplayArea = DisplayAreaInfo(MockToken().token(), SECOND_DISPLAY, 0)
    whenever(rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(SECOND_DISPLAY))
        .thenReturn(secondDisplayArea)

    val task = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    controller.moveToNextDisplay(task.taskId)
    with(getLatestWct(type = TRANSIT_CHANGE)) {
      assertThat(hierarchyOps).hasSize(1)
      assertThat(hierarchyOps[0].container).isEqualTo(task.token.asBinder())
      assertThat(hierarchyOps[0].isReparent).isTrue()
      assertThat(hierarchyOps[0].newParent).isEqualTo(secondDisplayArea.token.asBinder())
      assertThat(hierarchyOps[0].toTop).isTrue()
    }
  }

  @Test
  fun moveToNextDisplay_moveFromSecondToFirstDisplay() {
    // Set up two display ids
    whenever(rootTaskDisplayAreaOrganizer.displayIds)
        .thenReturn(intArrayOf(DEFAULT_DISPLAY, SECOND_DISPLAY))
    // Create a mock for the target display area: default display
    val defaultDisplayArea = DisplayAreaInfo(MockToken().token(), DEFAULT_DISPLAY, 0)
    whenever(rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY))
        .thenReturn(defaultDisplayArea)

    val task = setUpFreeformTask(displayId = SECOND_DISPLAY)
    controller.moveToNextDisplay(task.taskId)

    with(getLatestWct(type = TRANSIT_CHANGE)) {
      assertThat(hierarchyOps).hasSize(1)
      assertThat(hierarchyOps[0].container).isEqualTo(task.token.asBinder())
      assertThat(hierarchyOps[0].isReparent).isTrue()
      assertThat(hierarchyOps[0].newParent).isEqualTo(defaultDisplayArea.token.asBinder())
      assertThat(hierarchyOps[0].toTop).isTrue()
    }
  }

  @Test
  fun getTaskWindowingMode() {
    val fullscreenTask = setUpFullscreenTask()
    val freeformTask = setUpFreeformTask()

    assertThat(controller.getTaskWindowingMode(fullscreenTask.taskId))
        .isEqualTo(WINDOWING_MODE_FULLSCREEN)
    assertThat(controller.getTaskWindowingMode(freeformTask.taskId))
        .isEqualTo(WINDOWING_MODE_FREEFORM)
    assertThat(controller.getTaskWindowingMode(999)).isEqualTo(WINDOWING_MODE_UNDEFINED)
  }

  @Test
  fun onDesktopWindowClose_noActiveTasks() {
    val wct = WindowContainerTransaction()
    controller.onDesktopWindowClose(wct, displayId = DEFAULT_DISPLAY, taskId = 1)
    // Doesn't modify transaction
    assertThat(wct.hierarchyOps).isEmpty()
  }

  @Test
  fun onDesktopWindowClose_singleActiveTask_noWallpaperActivityToken() {
    val task = setUpFreeformTask()
    val wct = WindowContainerTransaction()
    controller.onDesktopWindowClose(wct, displayId = DEFAULT_DISPLAY, taskId = task.taskId)
    // Doesn't modify transaction
    assertThat(wct.hierarchyOps).isEmpty()
  }

  @Test
  fun onDesktopWindowClose_singleActiveTask_hasWallpaperActivityToken() {
    val task = setUpFreeformTask()
    val wallpaperToken = MockToken().token()
    taskRepository.wallpaperActivityToken = wallpaperToken

    val wct = WindowContainerTransaction()
    controller.onDesktopWindowClose(wct, displayId = DEFAULT_DISPLAY, taskId = task.taskId)
    // Adds remove wallpaper operation
    wct.assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  fun onDesktopWindowClose_singleActiveTask_isClosing() {
    val task = setUpFreeformTask()
    val wallpaperToken = MockToken().token()
    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.addClosingTask(DEFAULT_DISPLAY, task.taskId)

    val wct = WindowContainerTransaction()
    controller.onDesktopWindowClose(wct, displayId = DEFAULT_DISPLAY, taskId = task.taskId)
    // Doesn't modify transaction
    assertThat(wct.hierarchyOps).isEmpty()
  }

  @Test
  fun onDesktopWindowClose_singleActiveTask_isMinimized() {
    val task = setUpFreeformTask()
    val wallpaperToken = MockToken().token()
    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.minimizeTask(DEFAULT_DISPLAY, task.taskId)

    val wct = WindowContainerTransaction()
    controller.onDesktopWindowClose(wct, displayId = DEFAULT_DISPLAY, taskId = task.taskId)
    // Doesn't modify transaction
    assertThat(wct.hierarchyOps).isEmpty()
  }

  @Test
  fun onDesktopWindowClose_multipleActiveTasks() {
    val task1 = setUpFreeformTask()
    setUpFreeformTask()
    val wallpaperToken = MockToken().token()
    taskRepository.wallpaperActivityToken = wallpaperToken

    val wct = WindowContainerTransaction()
    controller.onDesktopWindowClose(wct, displayId = DEFAULT_DISPLAY, taskId = task1.taskId)
    // Doesn't modify transaction
    assertThat(wct.hierarchyOps).isEmpty()
  }

  @Test
  fun onDesktopWindowClose_multipleActiveTasks_isOnlyNonClosingTask() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    val wallpaperToken = MockToken().token()
    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.addClosingTask(DEFAULT_DISPLAY, task2.taskId)

    val wct = WindowContainerTransaction()
    controller.onDesktopWindowClose(wct, displayId = DEFAULT_DISPLAY, taskId = task1.taskId)
    // Adds remove wallpaper operation
    wct.assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  fun onDesktopWindowClose_multipleActiveTasks_hasMinimized() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    val wallpaperToken = MockToken().token()
    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.minimizeTask(DEFAULT_DISPLAY, task2.taskId)

    val wct = WindowContainerTransaction()
    controller.onDesktopWindowClose(wct, displayId = DEFAULT_DISPLAY, taskId = task1.taskId)
    // Adds remove wallpaper operation
    wct.assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  fun onDesktopWindowMinimize_noActiveTask_doesntUpdateTransaction() {
    val wct = WindowContainerTransaction()
    controller.onDesktopWindowMinimize(wct, taskId = 1)
    // Nothing happens.
    assertThat(wct.hierarchyOps).isEmpty()
  }

  @Test
  fun onDesktopWindowMinimize_singleActiveTask_noWallpaperActivityToken_doesntUpdateTransaction() {
    val task = setUpFreeformTask()
    val wct = WindowContainerTransaction()
    controller.onDesktopWindowMinimize(wct, taskId = task.taskId)
    // Nothing happens.
    assertThat(wct.hierarchyOps).isEmpty()
  }

  @Test
  fun onDesktopWindowMinimize_singleActiveTask_hasWallpaperActivityToken_removesWallpaper() {
    val task = setUpFreeformTask()
    val wallpaperToken = MockToken().token()
    taskRepository.wallpaperActivityToken = wallpaperToken

    val wct = WindowContainerTransaction()
    // The only active task is being minimized.
    controller.onDesktopWindowMinimize(wct, taskId = task.taskId)
    // Adds remove wallpaper operation
    wct.assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  fun onDesktopWindowMinimize_singleActiveTask_alreadyMinimized_doesntUpdateTransaction() {
    val task = setUpFreeformTask()
    val wallpaperToken = MockToken().token()
    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.minimizeTask(DEFAULT_DISPLAY, task.taskId)

    val wct = WindowContainerTransaction()
    // The only active task is already minimized.
    controller.onDesktopWindowMinimize(wct, taskId = task.taskId)
    // Doesn't modify transaction
    assertThat(wct.hierarchyOps).isEmpty()
  }

  @Test
  fun onDesktopWindowMinimize_multipleActiveTasks_doesntUpdateTransaction() {
    val task1 = setUpFreeformTask()
    setUpFreeformTask()
    val wallpaperToken = MockToken().token()
    taskRepository.wallpaperActivityToken = wallpaperToken

    val wct = WindowContainerTransaction()
    controller.onDesktopWindowMinimize(wct, taskId = task1.taskId)
    // Doesn't modify transaction
    assertThat(wct.hierarchyOps).isEmpty()
  }

  @Test
  fun onDesktopWindowMinimize_multipleActiveTasks_minimizesTheOnlyVisibleTask_removesWallpaper() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    val wallpaperToken = MockToken().token()
    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.minimizeTask(DEFAULT_DISPLAY, task2.taskId)

    val wct = WindowContainerTransaction()
    // task1 is the only visible task as task2 is minimized.
    controller.onDesktopWindowMinimize(wct, taskId = task1.taskId)
    // Adds remove wallpaper operation
    wct.assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  fun handleRequest_fullscreenTask_freeformVisible_returnSwitchToFreeformWCT() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val homeTask = setUpHomeTask()
    val freeformTask = setUpFreeformTask()
    markTaskVisible(freeformTask)
    val fullscreenTask = createFullscreenTask()

    val wct = controller.handleRequest(Binder(), createTransition(fullscreenTask))

    assertNotNull(wct, "should handle request")
    assertThat(wct.changes[fullscreenTask.token.asBinder()]?.windowingMode)
        .isEqualTo(WINDOWING_MODE_FREEFORM)

    assertThat(wct.hierarchyOps).hasSize(1)
  }

  @Test
  fun handleRequest_fullscreenTaskWithTaskOnHome_freeformVisible_returnSwitchToFreeformWCT() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val homeTask = setUpHomeTask()
    val freeformTask = setUpFreeformTask()
    markTaskVisible(freeformTask)
    val fullscreenTask = createFullscreenTask()
    fullscreenTask.baseIntent.setFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME)

    val wct = controller.handleRequest(Binder(), createTransition(fullscreenTask))

    assertNotNull(wct, "should handle request")
    assertThat(wct.changes[fullscreenTask.token.asBinder()]?.windowingMode)
      .isEqualTo(WINDOWING_MODE_FREEFORM)

    // There are 5 hops that are happening in this case:
    // 1. Moving the fullscreen task to top as we add moveToDesktop() changes
    // 2. Bringing home task to front
    // 3. Pending intent for the wallpaper
    // 4. Bringing the existing freeform task to top
    // 5. Bringing the fullscreen task back at the top
    assertThat(wct.hierarchyOps).hasSize(5)
    wct.assertReorderAt(1, homeTask, toTop = true)
    wct.assertReorderAt(4, fullscreenTask, toTop = true)
  }

  @Test
  fun handleRequest_fullscreenTaskToFreeform_underTaskLimit_dontMinimize() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val freeformTask = setUpFreeformTask()
    markTaskVisible(freeformTask)
    val fullscreenTask = createFullscreenTask()

    val wct = controller.handleRequest(Binder(), createTransition(fullscreenTask))

    // Make sure we only reorder the new task to top (we don't reorder the old task to bottom)
    assertThat(wct?.hierarchyOps?.size).isEqualTo(1)
    wct!!.assertReorderAt(0, fullscreenTask, toTop = true)
  }

  @Test
  fun handleRequest_fullscreenTaskToFreeform_bringsTasksOverLimit_otherTaskIsMinimized() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val freeformTasks = (1..MAX_TASK_LIMIT).map { _ -> setUpFreeformTask() }
    freeformTasks.forEach { markTaskVisible(it) }
    val fullscreenTask = createFullscreenTask()

    val wct = controller.handleRequest(Binder(), createTransition(fullscreenTask))

    // Make sure we reorder the new task to top, and the back task to the bottom
    assertThat(wct!!.hierarchyOps.size).isEqualTo(2)
    wct.assertReorderAt(0, fullscreenTask, toTop = true)
    wct.assertReorderAt(1, freeformTasks[0], toTop = false)
  }

  @Test
  fun handleRequest_fullscreenTaskWithTaskOnHome_bringsTasksOverLimit_otherTaskIsMinimized() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val freeformTasks = (1..MAX_TASK_LIMIT).map { _ -> setUpFreeformTask() }
    freeformTasks.forEach { markTaskVisible(it) }
    val fullscreenTask = createFullscreenTask()
    fullscreenTask.baseIntent.setFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME)

    val wct = controller.handleRequest(Binder(), createTransition(fullscreenTask))

    // Make sure we reorder the new task to top, and the back task to the bottom
    assertThat(wct!!.hierarchyOps.size).isEqualTo(9)
    wct.assertReorderAt(0, fullscreenTask, toTop = true)
    wct.assertReorderAt(8, freeformTasks[0], toTop = false)
  }

  @Test
  fun handleRequest_fullscreenTaskWithTaskOnHome_beyondLimit_existingAndNewTasksAreMinimized() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val minimizedTask = setUpFreeformTask()
    taskRepository.minimizeTask(displayId = DEFAULT_DISPLAY, taskId = minimizedTask.taskId)
    val freeformTasks = (1..MAX_TASK_LIMIT).map { _ -> setUpFreeformTask() }
    freeformTasks.forEach { markTaskVisible(it) }
    val homeTask = setUpHomeTask()
    val fullscreenTask = createFullscreenTask()
    fullscreenTask.baseIntent.setFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME)

    val wct = controller.handleRequest(Binder(), createTransition(fullscreenTask))

    assertThat(wct!!.hierarchyOps.size).isEqualTo(10)
    wct.assertReorderAt(0, fullscreenTask, toTop = true)
    // Make sure we reorder the home task to the top, desktop tasks to top of them and minimized
    // task is under the home task.
    wct.assertReorderAt(1, homeTask, toTop = true)
    wct.assertReorderAt(9, freeformTasks[0], toTop = false)
  }

  @Test
  fun handleRequest_fullscreenTask_noTasks_enforceDesktop_freeformDisplay_returnFreeformWCT() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)
    whenever(DesktopModeStatus.enterDesktopByDefaultOnFreeformDisplay(context)).thenReturn(true)
    val tda = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY)!!
    tda.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FREEFORM

    val fullscreenTask = createFullscreenTask()
    val wct = controller.handleRequest(Binder(), createTransition(fullscreenTask))

    assertNotNull(wct, "should handle request")
    assertThat(wct.changes[fullscreenTask.token.asBinder()]?.windowingMode)
        .isEqualTo(WINDOWING_MODE_UNDEFINED)
    assertThat(wct.hierarchyOps).hasSize(1)
    wct.assertReorderAt(0, fullscreenTask, toTop = true)
  }

  @Test
  fun handleRequest_fullscreenTask_noTasks_enforceDesktop_fullscreenDisplay_returnNull() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)
    whenever(DesktopModeStatus.enterDesktopByDefaultOnFreeformDisplay(context)).thenReturn(true)
    val tda = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY)!!
    tda.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN

    val fullscreenTask = createFullscreenTask()
    val wct = controller.handleRequest(Binder(), createTransition(fullscreenTask))

    assertThat(wct).isNull()
  }

  @Test
  fun handleRequest_fullscreenTask_freeformNotVisible_returnNull() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val freeformTask = setUpFreeformTask()
    markTaskHidden(freeformTask)
    val fullscreenTask = createFullscreenTask()
    assertThat(controller.handleRequest(Binder(), createTransition(fullscreenTask))).isNull()
  }

  @Test
  fun handleRequest_fullscreenTask_noOtherTasks_returnNull() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val fullscreenTask = createFullscreenTask()
    assertThat(controller.handleRequest(Binder(), createTransition(fullscreenTask))).isNull()
  }

  @Test
  fun handleRequest_fullscreenTask_freeformTaskOnOtherDisplay_returnNull() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val fullscreenTaskDefaultDisplay = createFullscreenTask(displayId = DEFAULT_DISPLAY)
    createFreeformTask(displayId = SECOND_DISPLAY)

    val result = controller.handleRequest(Binder(), createTransition(fullscreenTaskDefaultDisplay))
    assertThat(result).isNull()
  }

  @Test
  fun handleRequest_freeformTask_freeformVisible_aboveTaskLimit_minimize() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val freeformTasks = (1..MAX_TASK_LIMIT).map { _ -> setUpFreeformTask() }
    freeformTasks.forEach { markTaskVisible(it) }
    val newFreeformTask = createFreeformTask()

    val wct = controller.handleRequest(Binder(), createTransition(newFreeformTask, TRANSIT_OPEN))

    assertThat(wct?.hierarchyOps?.size).isEqualTo(1)
    wct!!.assertReorderAt(0, freeformTasks[0], toTop = false) // Reorder to the bottom
  }

  @Test
  fun handleRequest_freeformTask_relaunchActiveTask_taskBecomesUndefined() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val freeformTask = setUpFreeformTask()
    markTaskHidden(freeformTask)

    val wct =
      controller.handleRequest(Binder(), createTransition(freeformTask))

    // Should become undefined as the TDA is set to fullscreen. It will inherit from the TDA.
    assertNotNull(wct, "should handle request")
    assertThat(wct.changes[freeformTask.token.asBinder()]?.windowingMode)
      .isEqualTo(WINDOWING_MODE_UNDEFINED)
  }

  @Test
  fun handleRequest_freeformTask_relaunchTask_enforceDesktop_freeformDisplay_noWinModeChange() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)
    whenever(DesktopModeStatus.enterDesktopByDefaultOnFreeformDisplay(context)).thenReturn(true)
    val tda = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY)!!
    tda.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FREEFORM

    val freeformTask = setUpFreeformTask()
    markTaskHidden(freeformTask)
    val wct = controller.handleRequest(Binder(), createTransition(freeformTask))

    assertNotNull(wct, "should handle request")
    assertFalse(wct.anyWindowingModeChange(freeformTask.token))
  }

  @Test
  fun handleRequest_freeformTask_relaunchTask_enforceDesktop_fullscreenDisplay_becomesUndefined() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)
    whenever(DesktopModeStatus.enterDesktopByDefaultOnFreeformDisplay(context)).thenReturn(true)
    val tda = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY)!!
    tda.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN

    val freeformTask = setUpFreeformTask()
    markTaskHidden(freeformTask)
    val wct = controller.handleRequest(Binder(), createTransition(freeformTask))

    assertNotNull(wct, "should handle request")
    assertThat(wct.changes[freeformTask.token.asBinder()]?.windowingMode)
      .isEqualTo(WINDOWING_MODE_UNDEFINED)
  }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun handleRequest_freeformTask_desktopWallpaperDisabled_freeformNotVisible_reorderedToTop() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val freeformTask1 = setUpFreeformTask()
    val freeformTask2 = createFreeformTask()

    markTaskHidden(freeformTask1)
    val result =
        controller.handleRequest(Binder(), createTransition(freeformTask2, type = TRANSIT_TO_FRONT))

    assertNotNull(result, "Should handle request")
    assertThat(result.hierarchyOps?.size).isEqualTo(2)
    result.assertReorderAt(1, freeformTask2, toTop = true)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun handleRequest_freeformTask_desktopWallpaperEnabled_freeformNotVisible_reorderedToTop() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val freeformTask1 = setUpFreeformTask()
    val freeformTask2 = createFreeformTask()

    markTaskHidden(freeformTask1)
    val result =
      controller.handleRequest(Binder(), createTransition(freeformTask2, type = TRANSIT_TO_FRONT))

    assertNotNull(result, "Should handle request")
    assertThat(result.hierarchyOps?.size).isEqualTo(3)
    // Add desktop wallpaper activity
    result.assertPendingIntentAt(0, desktopWallpaperIntent)
    // Bring active desktop tasks to front
    result.assertReorderAt(1, freeformTask1, toTop = true)
    // Bring new task to front
    result.assertReorderAt(2, freeformTask2, toTop = true)
  }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun handleRequest_freeformTask_desktopWallpaperDisabled_noOtherTasks_reorderedToTop() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val task = createFreeformTask()
    val result = controller.handleRequest(Binder(), createTransition(task))

    assertNotNull(result, "Should handle request")
    assertThat(result.hierarchyOps?.size).isEqualTo(1)
    result.assertReorderAt(0, task, toTop = true)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun handleRequest_freeformTask_desktopWallpaperEnabled_noOtherTasks_reorderedToTop() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val task = createFreeformTask()
    val result = controller.handleRequest(Binder(), createTransition(task))

    assertNotNull(result, "Should handle request")
    assertThat(result.hierarchyOps?.size).isEqualTo(2)
    // Add desktop wallpaper activity
    result.assertPendingIntentAt(0, desktopWallpaperIntent)
    // Bring new task to front
    result.assertReorderAt(1, task, toTop = true)
  }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun handleRequest_freeformTask_dskWallpaperDisabled_freeformOnOtherDisplayOnly_reorderedToTop() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val taskDefaultDisplay = createFreeformTask(displayId = DEFAULT_DISPLAY)
    // Second display task
    createFreeformTask(displayId = SECOND_DISPLAY)

    val result = controller.handleRequest(Binder(), createTransition(taskDefaultDisplay))

    assertNotNull(result, "Should handle request")
    assertThat(result.hierarchyOps?.size).isEqualTo(1)
    result.assertReorderAt(0, taskDefaultDisplay, toTop = true)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  fun handleRequest_freeformTask_dskWallpaperEnabled_freeformOnOtherDisplayOnly_reorderedToTop() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val taskDefaultDisplay = createFreeformTask(displayId = DEFAULT_DISPLAY)
    // Second display task
    createFreeformTask(displayId = SECOND_DISPLAY)

    val result = controller.handleRequest(Binder(), createTransition(taskDefaultDisplay))

    assertNotNull(result, "Should handle request")
    assertThat(result.hierarchyOps?.size).isEqualTo(2)
    // Add desktop wallpaper activity
    result.assertPendingIntentAt(0, desktopWallpaperIntent)
    // Bring new task to front
    result.assertReorderAt(1, taskDefaultDisplay, toTop = true)
  }

  @Test
  fun handleRequest_freeformTask_alreadyInDesktop_noOverrideDensity_noConfigDensityChange() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)
    whenever(DesktopModeStatus.useDesktopOverrideDensity()).thenReturn(false)

    val freeformTask1 = setUpFreeformTask()
    markTaskVisible(freeformTask1)

    val freeformTask2 = createFreeformTask()
    val result =
        controller.handleRequest(freeformTask2.token.asBinder(), createTransition(freeformTask2))
    assertFalse(result.anyDensityConfigChange(freeformTask2.token))
  }

  @Test
  fun handleRequest_freeformTask_alreadyInDesktop_overrideDensity_hasConfigDensityChange() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)
    whenever(DesktopModeStatus.useDesktopOverrideDensity()).thenReturn(true)

    val freeformTask1 = setUpFreeformTask()
    markTaskVisible(freeformTask1)

    val freeformTask2 = createFreeformTask()
    val result =
        controller.handleRequest(freeformTask2.token.asBinder(), createTransition(freeformTask2))
    assertTrue(result.anyDensityConfigChange(freeformTask2.token))
  }

  @Test
  fun handleRequest_freeformTask_keyguardLocked_returnNull() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)
    whenever(keyguardManager.isKeyguardLocked).thenReturn(true)
    val freeformTask = createFreeformTask(displayId = DEFAULT_DISPLAY)

    val result = controller.handleRequest(Binder(), createTransition(freeformTask))

    assertNull(result, "Should NOT handle request")
  }

  @Test
  fun handleRequest_notOpenOrToFrontTransition_returnNull() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val task =
        TestRunningTaskInfoBuilder()
            .setActivityType(ACTIVITY_TYPE_STANDARD)
            .setWindowingMode(WINDOWING_MODE_FULLSCREEN)
            .build()
    val transition = createTransition(task = task, type = TRANSIT_CLOSE)
    val result = controller.handleRequest(Binder(), transition)
    assertThat(result).isNull()
  }

  @Test
  fun handleRequest_noTriggerTask_returnNull() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)
    assertThat(controller.handleRequest(Binder(), createTransition(task = null))).isNull()
  }

  @Test
  fun handleRequest_triggerTaskNotStandard_returnNull() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)
    val task = TestRunningTaskInfoBuilder().setActivityType(ACTIVITY_TYPE_HOME).build()
    assertThat(controller.handleRequest(Binder(), createTransition(task))).isNull()
  }

  @Test
  fun handleRequest_triggerTaskNotFullscreenOrFreeform_returnNull() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)

    val task =
        TestRunningTaskInfoBuilder()
            .setActivityType(ACTIVITY_TYPE_STANDARD)
            .setWindowingMode(WINDOWING_MODE_MULTI_WINDOW)
            .build()
    assertThat(controller.handleRequest(Binder(), createTransition(task))).isNull()
  }

  @Test
  fun handleRequest_recentsAnimationRunning_returnNull() {
    // Set up a visible freeform task so a fullscreen task should be converted to freeform
    val freeformTask = setUpFreeformTask()
    markTaskVisible(freeformTask)

    // Mark recents animation running
    recentsTransitionStateListener.onAnimationStateChanged(true)

    // Open a fullscreen task, check that it does not result in a WCT with changes to it
    val fullscreenTask = createFullscreenTask()
    assertThat(controller.handleRequest(Binder(), createTransition(fullscreenTask))).isNull()
  }

  @Test
  fun handleRequest_recentsAnimationRunning_relaunchActiveTask_taskBecomesUndefined() {
    // Set up a visible freeform task
    val freeformTask = setUpFreeformTask()
    markTaskVisible(freeformTask)

    // Mark recents animation running
    recentsTransitionStateListener.onAnimationStateChanged(true)

    // Should become undefined as the TDA is set to fullscreen. It will inherit from the TDA.
    val result = controller.handleRequest(Binder(), createTransition(freeformTask))
    assertThat(result?.changes?.get(freeformTask.token.asBinder())?.windowingMode)
      .isEqualTo(WINDOWING_MODE_UNDEFINED)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODALS_POLICY)
  fun handleRequest_topActivityTransparentWithStyleFloating_returnSwitchToFreeformWCT() {
    val freeformTask = setUpFreeformTask()
    markTaskVisible(freeformTask)

    val task =
      setUpFullscreenTask().apply {
        isTopActivityTransparent = true
        isTopActivityStyleFloating = true
        numActivities = 1
      }

    val result = controller.handleRequest(Binder(), createTransition(task))
    assertThat(result?.changes?.get(task.token.asBinder())?.windowingMode)
            .isEqualTo(WINDOWING_MODE_FREEFORM)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODALS_POLICY)
  fun handleRequest_topActivityTransparentWithoutStyleFloating_returnSwitchToFullscreenWCT() {
    val task =
      setUpFreeformTask().apply {
        isTopActivityTransparent = true
        isTopActivityStyleFloating = false
        numActivities = 1
      }

    val result = controller.handleRequest(Binder(), createTransition(task))
    assertThat(result?.changes?.get(task.token.asBinder())?.windowingMode)
            .isEqualTo(WINDOWING_MODE_UNDEFINED) // inherited FULLSCREEN
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODALS_POLICY)
  fun handleRequest_systemUIActivity_returnSwitchToFullscreenWCT() {
    val task = setUpFreeformTask()

    // Set task as systemUI package
    val systemUIPackageName = context.resources.getString(
      com.android.internal.R.string.config_systemUi)
    val baseComponent = ComponentName(systemUIPackageName, /* class */ "")
    task.baseActivity = baseComponent

    val result = controller.handleRequest(Binder(), createTransition(task))
    assertThat(result?.changes?.get(task.token.asBinder())?.windowingMode)
            .isEqualTo(WINDOWING_MODE_UNDEFINED) // inherited FULLSCREEN
  }

  @Test
  @DisableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION,
  )
  fun handleRequest_backTransition_singleTaskNoToken_noWallpaper_noBackNav_doesNotHandle() {
    val task = setUpFreeformTask()

    val result = controller.handleRequest(Binder(), createTransition(task, type = TRANSIT_TO_BACK))

    assertNull(result, "Should not handle request")
  }

  @Test
  @EnableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_backTransition_singleTaskNoToken_withWallpaper_withBackNav_removesTask() {
    val task = setUpFreeformTask()

    val result = controller.handleRequest(Binder(), createTransition(task, type = TRANSIT_TO_BACK))

    assertNotNull(result, "Should handle request").assertRemoveAt(0, task.token)
  }

  @Test
  @EnableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
  )
  fun handleRequest_backTransition_singleTaskNoToken_withWallpaper_notInDesktop_doesNotHandle() {
    val task = setUpFreeformTask()
    markTaskHidden(task)

    val result = controller.handleRequest(Binder(), createTransition(task, type = TRANSIT_TO_BACK))

    assertNull(result, "Should not handle request")
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
  fun handleRequest_backTransition_singleTaskNoToken_noBackNav_doesNotHandle() {
    val task = setUpFreeformTask()

    val result = controller.handleRequest(Binder(), createTransition(task, type = TRANSIT_TO_BACK))

    assertNull(result, "Should not handle request")
  }

  @Test
  @DisableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_backTransition_singleTaskWithToken_noWallpaper_noBackNav_doesNotHandle() {
    val task = setUpFreeformTask()

    taskRepository.wallpaperActivityToken = MockToken().token()
    val result = controller.handleRequest(Binder(), createTransition(task, type = TRANSIT_TO_BACK))

    assertNull(result, "Should not handle request")
  }

  @Test
  @EnableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_backTransition_singleTask_withWallpaper_withBackNav_removesWallpaperAndTask() {
    val task = setUpFreeformTask()
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    val result = controller.handleRequest(Binder(), createTransition(task, type = TRANSIT_TO_BACK))

    // Should create remove wallpaper transaction
    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, wallpaperToken)
    result.assertRemoveAt(index = 1, task.token)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
  fun handleRequest_backTransition_singleTaskWithToken_noBackNav_removesWallpaper() {
    val task = setUpFreeformTask()
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    val result = controller.handleRequest(Binder(), createTransition(task, type = TRANSIT_TO_BACK))

    // Should create remove wallpaper transaction
    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  @DisableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_backTransition_multipleTasks_noWallpaper_noBackNav_doesNotHandle() {
    val task1 = setUpFreeformTask()
    setUpFreeformTask()

    taskRepository.wallpaperActivityToken = MockToken().token()
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_TO_BACK))

    assertNull(result, "Should not handle request")
  }

  @Test
  @EnableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_backTransition_multipleTasks_withWallpaper_withBackNav_removesTask() {
    val task1 = setUpFreeformTask()
    setUpFreeformTask()

    taskRepository.wallpaperActivityToken = MockToken().token()
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_TO_BACK))

    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, task1.token)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
  fun handleRequest_backTransition_multipleTasks_noBackNav_doesNotHandle() {
    val task1 = setUpFreeformTask()
    setUpFreeformTask()

    taskRepository.wallpaperActivityToken = MockToken().token()
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_TO_BACK))

    assertNull(result, "Should not handle request")
  }

  @Test
  @EnableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_backTransition_multipleTasksSingleNonClosing_removesWallpaperAndTask() {
    val task1 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val task2 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.addClosingTask(displayId = DEFAULT_DISPLAY, taskId = task2.taskId)
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_TO_BACK))

    // Should create remove wallpaper transaction
    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, wallpaperToken)
    result.assertRemoveAt(index = 1, task1.token)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
  fun handleRequest_backTransition_multipleTasksSingleNonClosing_noBackNav_removesWallpaper() {
    val task1 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val task2 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.addClosingTask(displayId = DEFAULT_DISPLAY, taskId = task2.taskId)
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_TO_BACK))

    // Should create remove wallpaper transaction
    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  @EnableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_backTransition_multipleTasksSingleNonMinimized_removesWallpaperAndTask() {
    val task1 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val task2 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.minimizeTask(displayId = DEFAULT_DISPLAY, taskId = task2.taskId)
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_TO_BACK))

    // Should create remove wallpaper transaction
    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, wallpaperToken)
    result.assertRemoveAt(index = 1, task1.token)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
  fun handleRequest_backTransition_multipleTasksSingleNonMinimized_noBackNav_removesWallpaper() {
    val task1 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val task2 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.minimizeTask(displayId = DEFAULT_DISPLAY, taskId = task2.taskId)
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_TO_BACK))

    // Should create remove wallpaper transaction
    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  @EnableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_backTransition_nonMinimizadTask_withWallpaper_withBackNav_removesWallpaper() {
    val task1 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val task2 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.minimizeTask(displayId = DEFAULT_DISPLAY, taskId = task2.taskId)
    // Task is being minimized so mark it as not visible.
    taskRepository
      .updateTaskVisibility(displayId = DEFAULT_DISPLAY, task2.taskId, false)
    val result = controller.handleRequest(Binder(), createTransition(task2, type = TRANSIT_TO_BACK))

    assertNull(result, "Should not handle request")
  }

  @Test
  @DisableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_closeTransition_singleTaskNoToken_noWallpaper_noBackNav_doesNotHandle() {
    val task = setUpFreeformTask()

    val result = controller.handleRequest(Binder(), createTransition(task, type = TRANSIT_CLOSE))

    assertNull(result, "Should not handle request")
  }

  @Test
  @EnableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_closeTransition_singleTaskNoToken_withWallpaper_withBackNav_removesTask() {
    val task = setUpFreeformTask()

    val result = controller.handleRequest(Binder(), createTransition(task, type = TRANSIT_CLOSE))

    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, task.token)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
  fun handleRequest_closeTransition_singleTaskNoToken_noBackNav_doesNotHandle() {
    val task = setUpFreeformTask()

    val result = controller.handleRequest(Binder(), createTransition(task, type = TRANSIT_CLOSE))

    assertNull(result, "Should not handle request")
  }

  @Test
  @DisableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_closeTransition_singleTaskWithToken_noWallpaper_noBackNav_doesNotHandle() {
    val task = setUpFreeformTask()

    taskRepository.wallpaperActivityToken = MockToken().token()
    val result = controller.handleRequest(Binder(), createTransition(task, type = TRANSIT_CLOSE))

    assertNull(result, "Should not handle request")
  }

  @Test
  @EnableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_closeTransition_singleTaskWithToken_removesWallpaperAndTask() {
    val task = setUpFreeformTask()
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    val result = controller.handleRequest(Binder(), createTransition(task, type = TRANSIT_CLOSE))

    // Should create remove wallpaper transaction
    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, wallpaperToken)
    result.assertRemoveAt(index = 1, task.token)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
  fun handleRequest_closeTransition_singleTaskWithToken_withWallpaper_noBackNav_removesWallpaper() {
    val task = setUpFreeformTask()
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    val result = controller.handleRequest(Binder(), createTransition(task, type = TRANSIT_CLOSE))

    // Should create remove wallpaper transaction
    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  @DisableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_closeTransition_multipleTasks_noWallpaper_noBackNav_doesNotHandle() {
    val task1 = setUpFreeformTask()
    setUpFreeformTask()

    taskRepository.wallpaperActivityToken = MockToken().token()
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_CLOSE))

    assertNull(result, "Should not handle request")
  }

  @Test
  @EnableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_closeTransition_multipleTasks_withWallpaper_withBackNav_removesTask() {
    val task1 = setUpFreeformTask()
    setUpFreeformTask()

    taskRepository.wallpaperActivityToken = MockToken().token()
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_CLOSE))

    assertNotNull(result, "Should handle request")
    result.assertRemoveAt(index = 0, task1.token)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
  fun handleRequest_closeTransition_multipleTasksFlagEnabled_noBackNav_doesNotHandle() {
    val task1 = setUpFreeformTask()
    setUpFreeformTask()

    taskRepository.wallpaperActivityToken = MockToken().token()
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_CLOSE))

    assertNull(result, "Should not handle request")
  }

  @Test
  @EnableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_closeTransition_multipleTasksSingleNonClosing_removesWallpaperAndTask() {
    val task1 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val task2 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.addClosingTask(displayId = DEFAULT_DISPLAY, taskId = task2.taskId)
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_CLOSE))

    // Should create remove wallpaper transaction
    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, wallpaperToken)
    result.assertRemoveAt(index = 1, task1.token)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
  fun handleRequest_closeTransition_multipleTasksSingleNonClosing_noBackNav_removesWallpaper() {
    val task1 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val task2 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.addClosingTask(displayId = DEFAULT_DISPLAY, taskId = task2.taskId)
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_CLOSE))

    // Should create remove wallpaper transaction
    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  @EnableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_closeTransition_multipleTasksOneNonMinimized_removesWallpaperAndTask() {
    val task1 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val task2 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.minimizeTask(displayId = DEFAULT_DISPLAY, taskId = task2.taskId)
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_CLOSE))

    // Should create remove wallpaper transaction
    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, wallpaperToken)
    result.assertRemoveAt(index = 1, task1.token)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
  fun handleRequest_closeTransition_multipleTasksSingleNonMinimized_noBackNav_removesWallpaper() {
    val task1 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val task2 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.minimizeTask(displayId = DEFAULT_DISPLAY, taskId = task2.taskId)
    val result = controller.handleRequest(Binder(), createTransition(task1, type = TRANSIT_CLOSE))

    // Should create remove wallpaper transaction
    assertNotNull(result, "Should handle request").assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  @EnableFlags(
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY,
    Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
  )
  fun handleRequest_closeTransition_minimizadTask_withWallpaper_withBackNav_removesWallpaper() {
    val task1 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val task2 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
    val wallpaperToken = MockToken().token()

    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.minimizeTask(displayId = DEFAULT_DISPLAY, taskId = task2.taskId)
    // Task is being minimized so mark it as not visible.
    taskRepository
      .updateTaskVisibility(displayId = DEFAULT_DISPLAY, task2.taskId, false)
    val result = controller.handleRequest(Binder(), createTransition(task2, type = TRANSIT_TO_BACK))

    assertNull(result, "Should not handle request")
  }

  @Test
  fun moveFocusedTaskToDesktop_fullscreenTaskIsMovedToDesktop() {
    val task1 = setUpFullscreenTask()
    val task2 = setUpFullscreenTask()
    val task3 = setUpFullscreenTask()

    task1.isFocused = true
    task2.isFocused = false
    task3.isFocused = false

    controller.moveFocusedTaskToDesktop(DEFAULT_DISPLAY, transitionSource = UNKNOWN)

    val wct = getLatestEnterDesktopWct()
    assertThat(wct.changes[task1.token.asBinder()]?.windowingMode)
        .isEqualTo(WINDOWING_MODE_FREEFORM)
  }

  @Test
  fun moveFocusedTaskToDesktop_splitScreenTaskIsMovedToDesktop() {
    val task1 = setUpSplitScreenTask()
    val task2 = setUpFullscreenTask()
    val task3 = setUpFullscreenTask()
    val task4 = setUpSplitScreenTask()

    task1.isFocused = true
    task2.isFocused = false
    task3.isFocused = false
    task4.isFocused = true

    task4.parentTaskId = task1.taskId

    controller.moveFocusedTaskToDesktop(DEFAULT_DISPLAY, transitionSource = UNKNOWN)

    val wct = getLatestEnterDesktopWct()
    assertThat(wct.changes[task4.token.asBinder()]?.windowingMode)
        .isEqualTo(WINDOWING_MODE_FREEFORM)
    verify(splitScreenController)
        .prepareExitSplitScreen(any(), anyInt(), eq(SplitScreenController.EXIT_REASON_DESKTOP_MODE))
  }

  @Test
  fun moveFocusedTaskToFullscreen() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    val task3 = setUpFreeformTask()

    task1.isFocused = false
    task2.isFocused = true
    task3.isFocused = false

    controller.enterFullscreen(DEFAULT_DISPLAY, transitionSource = UNKNOWN)

    val wct = getLatestExitDesktopWct()
    assertThat(wct.changes[task2.token.asBinder()]?.windowingMode)
        .isEqualTo(WINDOWING_MODE_UNDEFINED) // inherited FULLSCREEN
  }

  @Test
  fun moveFocusedTaskToFullscreen_onlyVisibleNonMinimizedTask_removesWallpaperActivity() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    val task3 = setUpFreeformTask()
    val wallpaperToken = MockToken().token()

    task1.isFocused = false
    task2.isFocused = true
    task3.isFocused = false
    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.minimizeTask(DEFAULT_DISPLAY, task1.taskId)
    taskRepository.updateTaskVisibility(DEFAULT_DISPLAY, task3.taskId,
      visible = false)

    controller.enterFullscreen(DEFAULT_DISPLAY, transitionSource = UNKNOWN)

    val wct = getLatestExitDesktopWct()
    val taskChange = assertNotNull(wct.changes[task2.token.asBinder()])
    assertThat(taskChange.windowingMode).isEqualTo(WINDOWING_MODE_UNDEFINED) // inherited FULLSCREEN
    wct.assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  fun moveFocusedTaskToFullscreen_multipleVisibleTasks_doesNotRemoveWallpaperActivity() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    val task3 = setUpFreeformTask()
    val wallpaperToken = MockToken().token()

    task1.isFocused = false
    task2.isFocused = true
    task3.isFocused = false
    taskRepository.wallpaperActivityToken = wallpaperToken
    controller.enterFullscreen(DEFAULT_DISPLAY, transitionSource = UNKNOWN)

    val wct = getLatestExitDesktopWct()
    val taskChange = assertNotNull(wct.changes[task2.token.asBinder()])
    assertThat(taskChange.windowingMode).isEqualTo(WINDOWING_MODE_UNDEFINED) // inherited FULLSCREEN
    // Does not remove wallpaper activity, as desktop still has visible desktop tasks
    assertThat(wct.hierarchyOps).isEmpty()
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun dragToDesktop_landscapeDevice_resizable_undefinedOrientation_defaultLandscapeBounds() {
    val spyController = spy(controller)
    whenever(spyController.getVisualIndicator()).thenReturn(desktopModeVisualIndicator)
    whenever(desktopModeVisualIndicator.updateIndicatorType(anyOrNull()))
        .thenReturn(DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR)

    val task = setUpFullscreenTask()
    setUpLandscapeDisplay()

    spyController.onDragPositioningEndThroughStatusBar(PointF(800f, 1280f), task, mockSurface)
    val wct = getLatestDragToDesktopWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(DEFAULT_LANDSCAPE_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun dragToDesktop_landscapeDevice_resizable_landscapeOrientation_defaultLandscapeBounds() {
    val spyController = spy(controller)
    whenever(spyController.getVisualIndicator()).thenReturn(desktopModeVisualIndicator)
    whenever(desktopModeVisualIndicator.updateIndicatorType(anyOrNull()))
        .thenReturn(DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR)

    val task = setUpFullscreenTask(screenOrientation = SCREEN_ORIENTATION_LANDSCAPE)
    setUpLandscapeDisplay()

    spyController.onDragPositioningEndThroughStatusBar(PointF(800f, 1280f), task, mockSurface)
    val wct = getLatestDragToDesktopWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(DEFAULT_LANDSCAPE_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun dragToDesktop_landscapeDevice_resizable_portraitOrientation_resizablePortraitBounds() {
    val spyController = spy(controller)
    whenever(spyController.getVisualIndicator()).thenReturn(desktopModeVisualIndicator)
    whenever(desktopModeVisualIndicator.updateIndicatorType(anyOrNull()))
        .thenReturn(DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR)

    val task =
        setUpFullscreenTask(screenOrientation = SCREEN_ORIENTATION_PORTRAIT, shouldLetterbox = true)
    setUpLandscapeDisplay()

    spyController.onDragPositioningEndThroughStatusBar(PointF(800f, 1280f), task, mockSurface)
    val wct = getLatestDragToDesktopWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(RESIZABLE_PORTRAIT_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun dragToDesktop_landscapeDevice_unResizable_landscapeOrientation_defaultLandscapeBounds() {
    val spyController = spy(controller)
    whenever(spyController.getVisualIndicator()).thenReturn(desktopModeVisualIndicator)
    whenever(desktopModeVisualIndicator.updateIndicatorType(anyOrNull()))
        .thenReturn(DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR)

    val task =
        setUpFullscreenTask(isResizable = false, screenOrientation = SCREEN_ORIENTATION_LANDSCAPE)
    setUpLandscapeDisplay()

    spyController.onDragPositioningEndThroughStatusBar(PointF(800f, 1280f), task, mockSurface)
    val wct = getLatestDragToDesktopWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(DEFAULT_LANDSCAPE_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun dragToDesktop_landscapeDevice_unResizable_portraitOrientation_unResizablePortraitBounds() {
    val spyController = spy(controller)
    whenever(spyController.getVisualIndicator()).thenReturn(desktopModeVisualIndicator)
    whenever(desktopModeVisualIndicator.updateIndicatorType(anyOrNull()))
        .thenReturn(DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR)

    val task =
        setUpFullscreenTask(
            isResizable = false,
            screenOrientation = SCREEN_ORIENTATION_PORTRAIT,
            shouldLetterbox = true)
    setUpLandscapeDisplay()

    spyController.onDragPositioningEndThroughStatusBar(PointF(800f, 1280f), task, mockSurface)
    val wct = getLatestDragToDesktopWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(UNRESIZABLE_PORTRAIT_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun dragToDesktop_portraitDevice_resizable_undefinedOrientation_defaultPortraitBounds() {
    val spyController = spy(controller)
    whenever(spyController.getVisualIndicator()).thenReturn(desktopModeVisualIndicator)
    whenever(desktopModeVisualIndicator.updateIndicatorType(anyOrNull()))
        .thenReturn(DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR)

    val task = setUpFullscreenTask(deviceOrientation = ORIENTATION_PORTRAIT)
    setUpPortraitDisplay()

    spyController.onDragPositioningEndThroughStatusBar(PointF(800f, 1280f), task, mockSurface)
    val wct = getLatestDragToDesktopWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(DEFAULT_PORTRAIT_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun dragToDesktop_portraitDevice_resizable_portraitOrientation_defaultPortraitBounds() {
    val spyController = spy(controller)
    whenever(spyController.getVisualIndicator()).thenReturn(desktopModeVisualIndicator)
    whenever(desktopModeVisualIndicator.updateIndicatorType(anyOrNull()))
        .thenReturn(DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR)

    val task =
        setUpFullscreenTask(
            deviceOrientation = ORIENTATION_PORTRAIT,
            screenOrientation = SCREEN_ORIENTATION_PORTRAIT)
    setUpPortraitDisplay()

    spyController.onDragPositioningEndThroughStatusBar(PointF(800f, 1280f), task, mockSurface)
    val wct = getLatestDragToDesktopWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(DEFAULT_PORTRAIT_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun dragToDesktop_portraitDevice_resizable_landscapeOrientation_resizableLandscapeBounds() {
    val spyController = spy(controller)
    whenever(spyController.getVisualIndicator()).thenReturn(desktopModeVisualIndicator)
    whenever(desktopModeVisualIndicator.updateIndicatorType(anyOrNull()))
        .thenReturn(DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR)

    val task =
        setUpFullscreenTask(
            deviceOrientation = ORIENTATION_PORTRAIT,
            screenOrientation = SCREEN_ORIENTATION_LANDSCAPE,
            shouldLetterbox = true)
    setUpPortraitDisplay()

    spyController.onDragPositioningEndThroughStatusBar(PointF(800f, 1280f), task, mockSurface)
    val wct = getLatestDragToDesktopWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(RESIZABLE_LANDSCAPE_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun dragToDesktop_portraitDevice_unResizable_portraitOrientation_defaultPortraitBounds() {
    val spyController = spy(controller)
    whenever(spyController.getVisualIndicator()).thenReturn(desktopModeVisualIndicator)
    whenever(desktopModeVisualIndicator.updateIndicatorType(anyOrNull()))
        .thenReturn(DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR)

    val task =
        setUpFullscreenTask(
            isResizable = false,
            deviceOrientation = ORIENTATION_PORTRAIT,
            screenOrientation = SCREEN_ORIENTATION_PORTRAIT)
    setUpPortraitDisplay()

    spyController.onDragPositioningEndThroughStatusBar(PointF(800f, 1280f), task, mockSurface)
    val wct = getLatestDragToDesktopWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(DEFAULT_PORTRAIT_BOUNDS)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
  fun dragToDesktop_portraitDevice_unResizable_landscapeOrientation_unResizableLandscapeBounds() {
    val spyController = spy(controller)
    whenever(spyController.getVisualIndicator()).thenReturn(desktopModeVisualIndicator)
    whenever(desktopModeVisualIndicator.updateIndicatorType(anyOrNull()))
        .thenReturn(DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR)

    val task =
        setUpFullscreenTask(
            isResizable = false,
            deviceOrientation = ORIENTATION_PORTRAIT,
            screenOrientation = SCREEN_ORIENTATION_LANDSCAPE,
            shouldLetterbox = true)
    setUpPortraitDisplay()

    spyController.onDragPositioningEndThroughStatusBar(PointF(200f, 200f), task, mockSurface)
    val wct = getLatestDragToDesktopWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(UNRESIZABLE_LANDSCAPE_BOUNDS)
  }

  @Test
  fun onDesktopDragMove_endsOutsideValidDragArea_snapsToValidBounds() {
    val task = setUpFreeformTask()
    val mockSurface = mock(SurfaceControl::class.java)
    val mockDisplayLayout = mock(DisplayLayout::class.java)
    whenever(displayController.getDisplayLayout(task.displayId)).thenReturn(mockDisplayLayout)
    whenever(mockDisplayLayout.stableInsets()).thenReturn(Rect(0, 100, 2000, 2000))
    controller.onDragPositioningMove(task, mockSurface, 200f, Rect(100, -100, 500, 1000))

    controller.onDragPositioningEnd(
        task,
        mockSurface,
        Point(100, -100), /* position */
        PointF(200f, -200f), /* inputCoordinate */
        Rect(100, -100, 500, 1000), /* currentDragBounds */
        Rect(0, 50, 2000, 2000), /* validDragArea */
        Rect() /* dragStartBounds */ )
    val rectAfterEnd = Rect(100, 50, 500, 1150)
    verify(transitions)
        .startTransition(
            eq(TRANSIT_CHANGE),
            Mockito.argThat { wct ->
              return@argThat wct.changes.any { (token, change) ->
                change.configuration.windowConfiguration.bounds == rectAfterEnd
              }
            },
            eq(null))
  }

  @Test
  fun onDesktopDragEnd_noIndicator_updatesTaskBounds() {
    val task = setUpFreeformTask()
    val spyController = spy(controller)
    val mockSurface = mock(SurfaceControl::class.java)
    val mockDisplayLayout = mock(DisplayLayout::class.java)
    whenever(displayController.getDisplayLayout(task.displayId)).thenReturn(mockDisplayLayout)
    whenever(mockDisplayLayout.stableInsets()).thenReturn(Rect(0, 100, 2000, 2000))
    spyController.onDragPositioningMove(task, mockSurface, 200f, Rect(100, 200, 500, 1000))

    val currentDragBounds = Rect(100, 200, 500, 1000)
    whenever(spyController.getVisualIndicator()).thenReturn(desktopModeVisualIndicator)
    whenever(desktopModeVisualIndicator.updateIndicatorType(anyOrNull()))
      .thenReturn(DesktopModeVisualIndicator.IndicatorType.NO_INDICATOR)

    spyController.onDragPositioningEnd(
      task,
      mockSurface,
      Point(100, 200), /* position */
      PointF(200f, 300f), /* inputCoordinate */
      currentDragBounds, /* currentDragBounds */
      Rect(0, 50, 2000, 2000) /* validDragArea */,
      Rect() /* dragStartBounds */)


    verify(transitions)
      .startTransition(
        eq(TRANSIT_CHANGE),
        Mockito.argThat { wct ->
          return@argThat wct.changes.any { (token, change) ->
            change.configuration.windowConfiguration.bounds == currentDragBounds
          }
        },
        eq(null))
  }

  @Test
  fun enterSplit_freeformTaskIsMovedToSplit() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    val task3 = setUpFreeformTask()

    task1.isFocused = false
    task2.isFocused = true
    task3.isFocused = false

    controller.enterSplit(DEFAULT_DISPLAY, leftOrTop = false)

    verify(splitScreenController)
        .requestEnterSplitSelect(
            eq(task2),
            any(),
            eq(SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT),
            eq(task2.configuration.windowConfiguration.bounds))
  }

  @Test
  fun enterSplit_onlyVisibleNonMinimizedTask_removesWallpaperActivity() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    val task3 = setUpFreeformTask()
    val wallpaperToken = MockToken().token()

    task1.isFocused = false
    task2.isFocused = true
    task3.isFocused = false
    taskRepository.wallpaperActivityToken = wallpaperToken
    taskRepository.minimizeTask(DEFAULT_DISPLAY, task1.taskId)
    taskRepository.updateTaskVisibility(DEFAULT_DISPLAY, task3.taskId,
      visible = false)

    controller.enterSplit(DEFAULT_DISPLAY, leftOrTop = false)

    val wctArgument = ArgumentCaptor.forClass(WindowContainerTransaction::class.java)
    verify(splitScreenController)
      .requestEnterSplitSelect(
        eq(task2),
        wctArgument.capture(),
        eq(SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT),
        eq(task2.configuration.windowConfiguration.bounds))
    // Removes wallpaper activity when leaving desktop
    wctArgument.value.assertRemoveAt(index = 0, wallpaperToken)
  }

  @Test
  fun enterSplit_multipleVisibleNonMinimizedTasks_removesWallpaperActivity() {
    val task1 = setUpFreeformTask()
    val task2 = setUpFreeformTask()
    val task3 = setUpFreeformTask()
    val wallpaperToken = MockToken().token()

    task1.isFocused = false
    task2.isFocused = true
    task3.isFocused = false
    taskRepository.wallpaperActivityToken = wallpaperToken

    controller.enterSplit(DEFAULT_DISPLAY, leftOrTop = false)

    val wctArgument = ArgumentCaptor.forClass(WindowContainerTransaction::class.java)
    verify(splitScreenController)
      .requestEnterSplitSelect(
        eq(task2),
        wctArgument.capture(),
        eq(SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT),
        eq(task2.configuration.windowConfiguration.bounds))
    // Does not remove wallpaper activity, as desktop still has visible desktop tasks
    assertThat(wctArgument.value.hierarchyOps).isEmpty()
  }

  @Test
  fun toggleBounds_togglesToStableBounds() {
    val bounds = Rect(0, 0, 100, 100)
    val task = setUpFreeformTask(DEFAULT_DISPLAY, bounds)

    controller.toggleDesktopTaskSize(task)
    // Assert bounds set to stable bounds
    val wct = getLatestToggleResizeDesktopTaskWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(STABLE_BOUNDS)
  }

  @Test
  fun snapToHalfScreen_getSnapBounds_calculatesBoundsForResizable() {
    val bounds = Rect(100, 100, 300, 300)
    val task = setUpFreeformTask(DEFAULT_DISPLAY, bounds).apply {
      topActivityInfo = ActivityInfo().apply {
        screenOrientation = SCREEN_ORIENTATION_LANDSCAPE
        configuration.windowConfiguration.appBounds = bounds
      }
      isResizeable = true
    }

    val currentDragBounds = Rect(0, 100, 200, 300)
    val expectedBounds = Rect(
      STABLE_BOUNDS.left, STABLE_BOUNDS.top, STABLE_BOUNDS.right / 2, STABLE_BOUNDS.bottom
    )

    controller.snapToHalfScreen(task, mockSurface, currentDragBounds, SnapPosition.LEFT)
    // Assert bounds set to stable bounds
    val wct = getLatestToggleResizeDesktopTaskWct(currentDragBounds)
    assertThat(findBoundsChange(wct, task)).isEqualTo(expectedBounds)
  }

  @Test
  fun snapToHalfScreen_snapBoundsWhenAlreadySnapped_animatesSurfaceWithoutWCT() {
    assumeTrue(ENABLE_SHELL_TRANSITIONS)
    // Set up task to already be in snapped-left bounds
    val bounds = Rect(
      STABLE_BOUNDS.left, STABLE_BOUNDS.top, STABLE_BOUNDS.right / 2, STABLE_BOUNDS.bottom
    )
    val task = setUpFreeformTask(DEFAULT_DISPLAY, bounds).apply {
      topActivityInfo = ActivityInfo().apply {
        screenOrientation = SCREEN_ORIENTATION_LANDSCAPE
        configuration.windowConfiguration.appBounds = bounds
      }
      isResizeable = true
    }

    // Attempt to snap left again
    val currentDragBounds = Rect(bounds).apply { offset(-100, 0) }
    controller.snapToHalfScreen(task, mockSurface, currentDragBounds, SnapPosition.LEFT)

    // Assert that task is NOT updated via WCT
    verify(toggleResizeDesktopTaskTransitionHandler, never()).startTransition(any(), any())

    // Assert that task leash is updated via Surface Animations
    verify(mReturnToDragStartAnimator).start(
      eq(task.taskId),
      eq(mockSurface),
      eq(currentDragBounds),
      eq(bounds),
      eq(true)
    )
  }

  @Test
  @DisableFlags(Flags.FLAG_DISABLE_NON_RESIZABLE_APP_SNAP_RESIZING)
  fun handleSnapResizingTask_nonResizable_snapsToHalfScreen() {
    val task = setUpFreeformTask(DEFAULT_DISPLAY, Rect(0, 0, 200, 100)).apply {
      isResizeable = false
    }
    val preDragBounds = Rect(100, 100, 400, 500)
    val currentDragBounds = Rect(0, 100, 300, 500)

    controller.handleSnapResizingTask(
      task, SnapPosition.LEFT, mockSurface, currentDragBounds, preDragBounds)
    val wct = getLatestToggleResizeDesktopTaskWct(currentDragBounds)
    assertThat(findBoundsChange(wct, task)).isEqualTo(
      Rect(STABLE_BOUNDS.left, STABLE_BOUNDS.top, STABLE_BOUNDS.right / 2, STABLE_BOUNDS.bottom))
  }

  @Test
  @EnableFlags(Flags.FLAG_DISABLE_NON_RESIZABLE_APP_SNAP_RESIZING)
  fun handleSnapResizingTask_nonResizable_startsRepositionAnimation() {
    val task = setUpFreeformTask(DEFAULT_DISPLAY, Rect(0, 0, 200, 100)).apply {
      isResizeable = false
    }
    val preDragBounds = Rect(100, 100, 400, 500)
    val currentDragBounds = Rect(0, 100, 300, 500)

    controller.handleSnapResizingTask(
      task, SnapPosition.LEFT, mockSurface, currentDragBounds, preDragBounds)
    verify(mReturnToDragStartAnimator).start(
      eq(task.taskId),
      eq(mockSurface),
      eq(currentDragBounds),
      eq(preDragBounds),
      eq(false)
    )
  }

  @Test
  fun toggleBounds_togglesToCalculatedBoundsForNonResizable() {
    val bounds = Rect(0, 0, 200, 100)
    val task = setUpFreeformTask(DEFAULT_DISPLAY, bounds).apply {
      topActivityInfo = ActivityInfo().apply {
        screenOrientation = SCREEN_ORIENTATION_LANDSCAPE
        configuration.windowConfiguration.appBounds = bounds
      }
      appCompatTaskInfo.topActivityLetterboxAppWidth = bounds.width()
      appCompatTaskInfo.topActivityLetterboxAppHeight = bounds.height()
      isResizeable = false
    }

    // Bounds should be 1000 x 500, vertically centered in the 1000 x 1000 stable bounds
    val expectedBounds = Rect(STABLE_BOUNDS.left, 250, STABLE_BOUNDS.right, 750)

    controller.toggleDesktopTaskSize(task)
    // Assert bounds set to stable bounds
    val wct = getLatestToggleResizeDesktopTaskWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(expectedBounds)
  }

  @Test
  fun toggleBounds_lastBoundsBeforeMaximizeSaved() {
    val bounds = Rect(0, 0, 100, 100)
    val task = setUpFreeformTask(DEFAULT_DISPLAY, bounds)

    controller.toggleDesktopTaskSize(task)
    assertThat(taskRepository.removeBoundsBeforeMaximize(task.taskId)).isEqualTo(bounds)
  }

  @Test
  fun toggleBounds_togglesFromStableBoundsToLastBoundsBeforeMaximize() {
    val boundsBeforeMaximize = Rect(0, 0, 100, 100)
    val task = setUpFreeformTask(DEFAULT_DISPLAY, boundsBeforeMaximize)

    // Maximize
    controller.toggleDesktopTaskSize(task)
    task.configuration.windowConfiguration.bounds.set(STABLE_BOUNDS)

    // Restore
    controller.toggleDesktopTaskSize(task)

    // Assert bounds set to last bounds before maximize
    val wct = getLatestToggleResizeDesktopTaskWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(boundsBeforeMaximize)
  }

  @Test
  fun toggleBounds_togglesFromStableBoundsToLastBoundsBeforeMaximize_nonResizeableEqualWidth() {
    val boundsBeforeMaximize = Rect(0, 0, 100, 100)
    val task = setUpFreeformTask(DEFAULT_DISPLAY, boundsBeforeMaximize).apply {
      isResizeable = false
    }

    // Maximize
    controller.toggleDesktopTaskSize(task)
    task.configuration.windowConfiguration.bounds.set(STABLE_BOUNDS.left,
      boundsBeforeMaximize.top, STABLE_BOUNDS.right, boundsBeforeMaximize.bottom)

    // Restore
    controller.toggleDesktopTaskSize(task)

    // Assert bounds set to last bounds before maximize
    val wct = getLatestToggleResizeDesktopTaskWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(boundsBeforeMaximize)
  }

  @Test
  fun toggleBounds_togglesFromStableBoundsToLastBoundsBeforeMaximize_nonResizeableEqualHeight() {
    val boundsBeforeMaximize = Rect(0, 0, 100, 100)
    val task = setUpFreeformTask(DEFAULT_DISPLAY, boundsBeforeMaximize).apply {
      isResizeable = false
    }

    // Maximize
    controller.toggleDesktopTaskSize(task)
    task.configuration.windowConfiguration.bounds.set(boundsBeforeMaximize.left,
      STABLE_BOUNDS.top, boundsBeforeMaximize.right, STABLE_BOUNDS.bottom)

    // Restore
    controller.toggleDesktopTaskSize(task)

    // Assert bounds set to last bounds before maximize
    val wct = getLatestToggleResizeDesktopTaskWct()
    assertThat(findBoundsChange(wct, task)).isEqualTo(boundsBeforeMaximize)
  }

  @Test
  fun toggleBounds_removesLastBoundsBeforeMaximizeAfterRestoringBounds() {
    val boundsBeforeMaximize = Rect(0, 0, 100, 100)
    val task = setUpFreeformTask(DEFAULT_DISPLAY, boundsBeforeMaximize)

    // Maximize
    controller.toggleDesktopTaskSize(task)
    task.configuration.windowConfiguration.bounds.set(STABLE_BOUNDS)

    // Restore
    controller.toggleDesktopTaskSize(task)

    // Assert last bounds before maximize removed after use
    assertThat(taskRepository.removeBoundsBeforeMaximize(task.taskId)).isNull()
  }


  @Test
  fun onUnhandledDrag_newFreeformIntent() {
    testOnUnhandledDrag(DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR,
      PointF(1200f, 700f),
      Rect(240, 700, 2160, 1900))
  }

  @Test
  fun onUnhandledDrag_newFreeformIntentSplitLeft() {
    testOnUnhandledDrag(DesktopModeVisualIndicator.IndicatorType.TO_SPLIT_LEFT_INDICATOR,
      PointF(50f, 700f),
      Rect(0, 0, 500, 1000))
  }

  @Test
  fun onUnhandledDrag_newFreeformIntentSplitRight() {
    testOnUnhandledDrag(DesktopModeVisualIndicator.IndicatorType.TO_SPLIT_RIGHT_INDICATOR,
      PointF(2500f, 700f),
      Rect(500, 0, 1000, 1000))
  }

  @Test
  fun onUnhandledDrag_newFullscreenIntent() {
    testOnUnhandledDrag(DesktopModeVisualIndicator.IndicatorType.TO_FULLSCREEN_INDICATOR,
      PointF(1200f, 50f),
      Rect())
  }

  /**
   * Assert that an unhandled drag event launches a PendingIntent with the
   * windowing mode and bounds we are expecting.
   */
  private fun testOnUnhandledDrag(
    indicatorType: DesktopModeVisualIndicator.IndicatorType,
    inputCoordinate: PointF,
    expectedBounds: Rect
  ) {
    setUpLandscapeDisplay()
    val task = setUpFreeformTask()
    markTaskVisible(task)
    task.isFocused = true
    val runningTasks = ArrayList<RunningTaskInfo>()
    runningTasks.add(task)
    val spyController = spy(controller)
    val mockPendingIntent = mock(PendingIntent::class.java)
    val mockDragEvent = mock(DragEvent::class.java)
    val mockCallback = mock(Consumer::class.java)
    val b = SurfaceControl.Builder()
    b.setName("test surface")
    val dragSurface = b.build()
    whenever(shellTaskOrganizer.runningTasks).thenReturn(runningTasks)
    whenever(mockDragEvent.dragSurface).thenReturn(dragSurface)
    whenever(mockDragEvent.x).thenReturn(inputCoordinate.x)
    whenever(mockDragEvent.y).thenReturn(inputCoordinate.y)
    whenever(multiInstanceHelper.supportsMultiInstanceSplit(anyOrNull())).thenReturn(true)
    whenever(spyController.getVisualIndicator()).thenReturn(desktopModeVisualIndicator)
    doReturn(indicatorType)
      .whenever(spyController).updateVisualIndicator(
        eq(task),
        anyOrNull(),
        anyOrNull(),
        anyOrNull(),
        eq(DesktopModeVisualIndicator.DragStartState.DRAGGED_INTENT)
      )

    spyController.onUnhandledDrag(
      mockPendingIntent,
      mockDragEvent,
      mockCallback as Consumer<Boolean>
    )
    val arg: ArgumentCaptor<WindowContainerTransaction> =
      ArgumentCaptor.forClass(WindowContainerTransaction::class.java)
    var expectedWindowingMode: Int
      if (indicatorType == DesktopModeVisualIndicator.IndicatorType.TO_FULLSCREEN_INDICATOR) {
        expectedWindowingMode = WINDOWING_MODE_FULLSCREEN
        // Fullscreen launches currently use default transitions
        verify(transitions).startTransition(any(), capture(arg), anyOrNull())
      } else {
        expectedWindowingMode = WINDOWING_MODE_FREEFORM
        // All other launches use a special handler.
        verify(dragAndDropTransitionHandler).handleDropEvent(capture(arg))
      }
    assertThat(ActivityOptions.fromBundle(arg.value.hierarchyOps[0].launchOptions)
      .launchWindowingMode).isEqualTo(expectedWindowingMode)
    assertThat(ActivityOptions.fromBundle(arg.value.hierarchyOps[0].launchOptions)
      .launchBounds).isEqualTo(expectedBounds)
  }

  private val desktopWallpaperIntent: Intent
    get() = Intent(context, DesktopWallpaperActivity::class.java)

  private fun addFreeformTaskAtPosition(
    pos: DesktopTaskPosition,
    stableBounds: Rect,
    bounds: Rect = DEFAULT_LANDSCAPE_BOUNDS,
    offsetPos: Point = Point(0, 0)
  ): RunningTaskInfo {
    val offset = pos.getTopLeftCoordinates(stableBounds, bounds)
    val prevTaskBounds = Rect(bounds)
    prevTaskBounds.offsetTo(offset.x + offsetPos.x, offset.y + offsetPos.y)
    return setUpFreeformTask(bounds = prevTaskBounds)
  }

  private fun setUpFreeformTask(
      displayId: Int = DEFAULT_DISPLAY,
      bounds: Rect? = null,
      active: Boolean = true
  ): RunningTaskInfo {
    val task = createFreeformTask(displayId, bounds)
    val activityInfo = ActivityInfo()
    task.topActivityInfo = activityInfo
    whenever(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
    if (active) {
      taskRepository.addActiveTask(displayId, task.taskId)
      taskRepository.updateTaskVisibility(displayId, task.taskId, visible = true)
    }
    taskRepository.addOrMoveFreeformTaskToTop(displayId, task.taskId)
    runningTasks.add(task)
    return task
  }

  private fun setUpHomeTask(displayId: Int = DEFAULT_DISPLAY): RunningTaskInfo {
    val task = createHomeTask(displayId)
    whenever(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
    runningTasks.add(task)
    return task
  }

  private fun setUpFullscreenTask(
    displayId: Int = DEFAULT_DISPLAY,
    isResizable: Boolean = true,
    windowingMode: Int = WINDOWING_MODE_FULLSCREEN,
    deviceOrientation: Int = ORIENTATION_LANDSCAPE,
    screenOrientation: Int = SCREEN_ORIENTATION_UNSPECIFIED,
    shouldLetterbox: Boolean = false,
    gravity: Int = Gravity.NO_GRAVITY,
    enableUserFullscreenOverride: Boolean = false,
    enableSystemFullscreenOverride: Boolean = false,
    aspectRatioOverrideApplied: Boolean = false
  ): RunningTaskInfo {
    val task = createFullscreenTask(displayId)
    val activityInfo = ActivityInfo()
    activityInfo.screenOrientation = screenOrientation
    activityInfo.windowLayout = ActivityInfo.WindowLayout(0, 0F, 0, 0F, gravity, 0, 0)
    with(task) {
      topActivityInfo = activityInfo
      isResizeable = isResizable
      configuration.orientation = deviceOrientation
      configuration.windowConfiguration.windowingMode = windowingMode
      appCompatTaskInfo.isUserFullscreenOverrideEnabled = enableUserFullscreenOverride
      appCompatTaskInfo.isSystemFullscreenOverrideEnabled = enableSystemFullscreenOverride

      if (deviceOrientation == ORIENTATION_LANDSCAPE) {
        configuration.windowConfiguration.appBounds =
          Rect(0, 0, DISPLAY_DIMENSION_LONG, DISPLAY_DIMENSION_SHORT)
        appCompatTaskInfo.topActivityLetterboxAppWidth = DISPLAY_DIMENSION_LONG
        appCompatTaskInfo.topActivityLetterboxAppHeight = DISPLAY_DIMENSION_SHORT
      } else {
        configuration.windowConfiguration.appBounds =
          Rect(0, 0, DISPLAY_DIMENSION_SHORT, DISPLAY_DIMENSION_LONG)
        appCompatTaskInfo.topActivityLetterboxAppWidth = DISPLAY_DIMENSION_SHORT
        appCompatTaskInfo.topActivityLetterboxAppHeight = DISPLAY_DIMENSION_LONG
      }

      if (shouldLetterbox) {
        appCompatTaskInfo.setHasMinAspectRatioOverride(aspectRatioOverrideApplied)
        if (deviceOrientation == ORIENTATION_LANDSCAPE &&
            screenOrientation == SCREEN_ORIENTATION_PORTRAIT) {
          // Letterbox to portrait size
          appCompatTaskInfo.setTopActivityLetterboxed(true)
          appCompatTaskInfo.topActivityLetterboxAppWidth = 1200
          appCompatTaskInfo.topActivityLetterboxAppHeight = 1600
        } else if (deviceOrientation == ORIENTATION_PORTRAIT &&
            screenOrientation == SCREEN_ORIENTATION_LANDSCAPE) {
          // Letterbox to landscape size
          appCompatTaskInfo.setTopActivityLetterboxed(true)
          appCompatTaskInfo.topActivityLetterboxAppWidth = 1600
          appCompatTaskInfo.topActivityLetterboxAppHeight = 1200
        }
      }
    }
    whenever(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
    runningTasks.add(task)
    return task
  }

  private fun setUpLandscapeDisplay() {
    whenever(displayLayout.width()).thenReturn(DISPLAY_DIMENSION_LONG)
    whenever(displayLayout.height()).thenReturn(DISPLAY_DIMENSION_SHORT)
    val stableBounds = Rect(0, 0, DISPLAY_DIMENSION_LONG,
      DISPLAY_DIMENSION_SHORT - Companion.TASKBAR_FRAME_HEIGHT
    )
    whenever(displayLayout.getStableBoundsForDesktopMode(any())).thenAnswer { i ->
      (i.arguments.first() as Rect).set(stableBounds)
    }
  }

  private fun setUpPortraitDisplay() {
    whenever(displayLayout.width()).thenReturn(DISPLAY_DIMENSION_SHORT)
    whenever(displayLayout.height()).thenReturn(DISPLAY_DIMENSION_LONG)
    val stableBounds = Rect(0, 0, DISPLAY_DIMENSION_SHORT,
      DISPLAY_DIMENSION_LONG - Companion.TASKBAR_FRAME_HEIGHT
    )
    whenever(displayLayout.getStableBoundsForDesktopMode(any())).thenAnswer { i ->
      (i.arguments.first() as Rect).set(stableBounds)
    }
  }

  private fun setUpSplitScreenTask(displayId: Int = DEFAULT_DISPLAY): RunningTaskInfo {
    val task = createSplitScreenTask(displayId)
    whenever(splitScreenController.isTaskInSplitScreen(task.taskId)).thenReturn(true)
    whenever(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
    runningTasks.add(task)
    return task
  }

  private fun markTaskVisible(task: RunningTaskInfo) {
    taskRepository.updateTaskVisibility(
        task.displayId, task.taskId, visible = true)
  }

  private fun markTaskHidden(task: RunningTaskInfo) {
    taskRepository.updateTaskVisibility(
        task.displayId, task.taskId, visible = false)
  }

  private fun getLatestWct(
      @WindowManager.TransitionType type: Int = TRANSIT_OPEN,
      handlerClass: Class<out TransitionHandler>? = null
  ): WindowContainerTransaction {
    val arg = ArgumentCaptor.forClass(WindowContainerTransaction::class.java)
    if (ENABLE_SHELL_TRANSITIONS) {
      if (handlerClass == null) {
        verify(transitions).startTransition(eq(type), arg.capture(), isNull())
      } else {
        verify(transitions).startTransition(eq(type), arg.capture(), isA(handlerClass))
      }
    } else {
      verify(shellTaskOrganizer).applyTransaction(arg.capture())
    }
    return arg.value
  }

  private fun getLatestToggleResizeDesktopTaskWct(
    currentBounds: Rect? = null
  ): WindowContainerTransaction {
    val arg: ArgumentCaptor<WindowContainerTransaction> =
        ArgumentCaptor.forClass(WindowContainerTransaction::class.java)
    if (ENABLE_SHELL_TRANSITIONS) {
      verify(toggleResizeDesktopTaskTransitionHandler, atLeastOnce())
        .startTransition(capture(arg), eq(currentBounds))
    } else {
      verify(shellTaskOrganizer).applyTransaction(capture(arg))
    }
    return arg.value
  }

  private fun getLatestEnterDesktopWct(): WindowContainerTransaction {
    val arg = ArgumentCaptor.forClass(WindowContainerTransaction::class.java)
    if (ENABLE_SHELL_TRANSITIONS) {
      verify(enterDesktopTransitionHandler).moveToDesktop(arg.capture(), any())
    } else {
      verify(shellTaskOrganizer).applyTransaction(arg.capture())
    }
    return arg.value
  }

  private fun getLatestDragToDesktopWct(): WindowContainerTransaction {
    val arg: ArgumentCaptor<WindowContainerTransaction> =
        ArgumentCaptor.forClass(WindowContainerTransaction::class.java)
    if (ENABLE_SHELL_TRANSITIONS) {
      verify(dragToDesktopTransitionHandler).finishDragToDesktopTransition(capture(arg))
    } else {
      verify(shellTaskOrganizer).applyTransaction(capture(arg))
    }
    return arg.value
  }

  private fun getLatestExitDesktopWct(): WindowContainerTransaction {
    val arg = ArgumentCaptor.forClass(WindowContainerTransaction::class.java)
    if (ENABLE_SHELL_TRANSITIONS) {
      verify(exitDesktopTransitionHandler).startTransition(any(), arg.capture(), any(), any())
    } else {
      verify(shellTaskOrganizer).applyTransaction(arg.capture())
    }
    return arg.value
  }

  private fun findBoundsChange(wct: WindowContainerTransaction, task: RunningTaskInfo): Rect? =
      wct.changes[task.token.asBinder()]?.configuration?.windowConfiguration?.bounds

  private fun verifyWCTNotExecuted() {
    if (ENABLE_SHELL_TRANSITIONS) {
      verify(transitions, never()).startTransition(anyInt(), any(), isNull())
    } else {
      verify(shellTaskOrganizer, never()).applyTransaction(any())
    }
  }

  private fun verifyExitDesktopWCTNotExecuted() {
    if (ENABLE_SHELL_TRANSITIONS) {
      verify(exitDesktopTransitionHandler, never()).startTransition(any(), any(), any(), any())
    } else {
      verify(shellTaskOrganizer, never()).applyTransaction(any())
    }
  }

  private fun verifyEnterDesktopWCTNotExecuted() {
    if (ENABLE_SHELL_TRANSITIONS) {
      verify(enterDesktopTransitionHandler, never()).moveToDesktop(any(), any())
    } else {
      verify(shellTaskOrganizer, never()).applyTransaction(any())
    }
  }

  private fun createTransition(
      task: RunningTaskInfo?,
      @WindowManager.TransitionType type: Int = TRANSIT_OPEN
  ): TransitionRequestInfo {
    return TransitionRequestInfo(type, task, null /* remoteTransition */)
  }

  private companion object {
    const val SECOND_DISPLAY = 2
    val STABLE_BOUNDS = Rect(0, 0, 1000, 1000)
    const val MAX_TASK_LIMIT = 6
    private const val TASKBAR_FRAME_HEIGHT = 200
  }
}

private fun WindowContainerTransaction.assertIndexInBounds(index: Int) {
  assertWithMessage("WCT does not have a hierarchy operation at index $index")
      .that(hierarchyOps.size)
      .isGreaterThan(index)
}

private fun WindowContainerTransaction.assertReorderAt(
    index: Int,
    task: RunningTaskInfo,
    toTop: Boolean? = null
) {
  assertIndexInBounds(index)
  val op = hierarchyOps[index]
  assertThat(op.type).isEqualTo(HIERARCHY_OP_TYPE_REORDER)
  assertThat(op.container).isEqualTo(task.token.asBinder())
  toTop?.let { assertThat(op.toTop).isEqualTo(it) }
}

private fun WindowContainerTransaction.assertReorderSequence(vararg tasks: RunningTaskInfo) {
  for (i in tasks.indices) {
    assertReorderAt(i, tasks[i])
  }
}

/** Checks if the reorder hierarchy operations in [range] correspond to [tasks] list */
private fun WindowContainerTransaction.assertReorderSequenceInRange(
  range: IntRange,
  vararg tasks: RunningTaskInfo
) {
  assertThat(hierarchyOps.slice(range).map { it.type to it.container })
    .containsExactlyElementsIn(tasks.map { HIERARCHY_OP_TYPE_REORDER to it.token.asBinder() })
    .inOrder()
}

private fun WindowContainerTransaction.assertRemoveAt(index: Int, token: WindowContainerToken) {
  assertIndexInBounds(index)
  val op = hierarchyOps[index]
  assertThat(op.type).isEqualTo(HIERARCHY_OP_TYPE_REMOVE_TASK)
  assertThat(op.container).isEqualTo(token.asBinder())
}

private fun WindowContainerTransaction.assertPendingIntentAt(index: Int, intent: Intent) {
  assertIndexInBounds(index)
  val op = hierarchyOps[index]
  assertThat(op.type).isEqualTo(HIERARCHY_OP_TYPE_PENDING_INTENT)
  assertThat(op.pendingIntent?.intent?.component).isEqualTo(intent.component)
}

private fun WindowContainerTransaction.assertLaunchTaskAt(
    index: Int,
    taskId: Int,
    windowingMode: Int
) {
  val keyLaunchWindowingMode = "android.activity.windowingMode"

  assertIndexInBounds(index)
  val op = hierarchyOps[index]
  assertThat(op.type).isEqualTo(HIERARCHY_OP_TYPE_LAUNCH_TASK)
  assertThat(op.launchOptions?.getInt(LAUNCH_KEY_TASK_ID)).isEqualTo(taskId)
  assertThat(op.launchOptions?.getInt(keyLaunchWindowingMode, WINDOWING_MODE_UNDEFINED))
      .isEqualTo(windowingMode)
}

private fun WindowContainerTransaction?.anyDensityConfigChange(
    token: WindowContainerToken
): Boolean {
  return this?.changes?.any { change ->
    change.key == token.asBinder() && ((change.value.configSetMask and CONFIG_DENSITY) != 0)
  } ?: false
}

private fun WindowContainerTransaction?.anyWindowingModeChange(
  token: WindowContainerToken
): Boolean {
return this?.changes?.any { change ->
  change.key == token.asBinder() && change.value.windowingMode >= 0
} ?: false
}

private fun createTaskInfo(id: Int) =
    RecentTaskInfo().apply {
      taskId = id
      token = WindowContainerToken(mock(IWindowContainerToken::class.java))
    }

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
package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.Choreographer
import android.view.Display.DEFAULT_DISPLAY
import android.view.InputChannel
import android.view.InputMonitor
import android.view.InsetsSource
import android.view.InsetsState
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.WindowInsets.Type.navigationBars
import android.view.WindowInsets.Type.statusBars
import androidx.test.filters.SmallTest
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayInsetsController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.desktopmode.DesktopTasksController
import com.android.wm.shell.sysui.KeyguardChangeListener
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.windowdecor.DesktopModeWindowDecorViewModel.DesktopModeOnInsetsChangedListener
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.util.Optional
import java.util.function.Supplier


/** Tests of [DesktopModeWindowDecorViewModel]  */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class DesktopModeWindowDecorViewModelTests : ShellTestCase() {
    @Mock private lateinit var mockDesktopModeWindowDecorFactory:
            DesktopModeWindowDecoration.Factory
    @Mock private lateinit var mockMainHandler: Handler
    @Mock private lateinit var mockMainChoreographer: Choreographer
    @Mock private lateinit var mockTaskOrganizer: ShellTaskOrganizer
    @Mock private lateinit var mockDisplayController: DisplayController
    @Mock private lateinit var mockDisplayLayout: DisplayLayout
    @Mock private lateinit var displayInsetsController: DisplayInsetsController
    @Mock private lateinit var mockSyncQueue: SyncTransactionQueue
    @Mock private lateinit var mockDesktopTasksController: DesktopTasksController
    @Mock private lateinit var mockInputMonitor: InputMonitor
    @Mock private lateinit var mockTransitions: Transitions
    @Mock private lateinit var mockInputMonitorFactory:
            DesktopModeWindowDecorViewModel.InputMonitorFactory
    @Mock private lateinit var mockShellController: ShellController
    @Mock private lateinit var mockShellExecutor: ShellExecutor
    @Mock private lateinit var mockRootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer
    @Mock private lateinit var mockShellCommandHandler: ShellCommandHandler

    private val transactionFactory = Supplier<SurfaceControl.Transaction> {
        SurfaceControl.Transaction()
    }

    private lateinit var shellInit: ShellInit
    private lateinit var desktopModeOnInsetsChangedListener: DesktopModeOnInsetsChangedListener
    private lateinit var desktopModeWindowDecorViewModel: DesktopModeWindowDecorViewModel

    @Before
    fun setUp() {
        shellInit = ShellInit(mockShellExecutor)
        desktopModeWindowDecorViewModel = DesktopModeWindowDecorViewModel(
                mContext,
                mockMainHandler,
                mockMainChoreographer,
                shellInit,
                mockShellCommandHandler,
                mockTaskOrganizer,
                mockDisplayController,
                mockShellController,
                displayInsetsController,
                mockSyncQueue,
                mockTransitions,
                Optional.of(mockDesktopTasksController),
                mockDesktopModeWindowDecorFactory,
                mockInputMonitorFactory,
                transactionFactory,
                mockRootTaskDisplayAreaOrganizer
        )

        whenever(mockDisplayController.getDisplayLayout(any())).thenReturn(mockDisplayLayout)
        whenever(mockDisplayLayout.stableInsets()).thenReturn(STABLE_INSETS)
        whenever(mockInputMonitorFactory.create(any(), any())).thenReturn(mockInputMonitor)

        // InputChannel cannot be mocked because it passes to InputEventReceiver.
        val inputChannels = InputChannel.openInputChannelPair(TAG)
        inputChannels.first().dispose()
        whenever(mockInputMonitor.inputChannel).thenReturn(inputChannels[1])

        shellInit.init()

        val listenerCaptor =
                argumentCaptor<DesktopModeWindowDecorViewModel.DesktopModeOnInsetsChangedListener>()
        verify(displayInsetsController).addInsetsChangedListener(anyInt(), listenerCaptor.capture())
        desktopModeOnInsetsChangedListener = listenerCaptor.firstValue
    }

    @Test
    fun testDeleteCaptionOnChangeTransitionWhenNecessary() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        val taskSurface = SurfaceControl()
        val decoration = setUpMockDecorationForTask(task)

        onTaskOpening(task, taskSurface)

        task.setWindowingMode(WINDOWING_MODE_UNDEFINED)
        task.setActivityType(ACTIVITY_TYPE_UNDEFINED)
        onTaskChanging(task, taskSurface)

        verify(mockDesktopModeWindowDecorFactory).create(
                mContext,
                mockDisplayController,
                mockTaskOrganizer,
                task,
                taskSurface,
                mockMainHandler,
                mockMainChoreographer,
                mockSyncQueue,
                mockRootTaskDisplayAreaOrganizer
        )
        verify(decoration).close()
    }

    @Test
    fun testCreateCaptionOnChangeTransitionWhenNecessary() {
        val task = createTask(
                windowingMode = WINDOWING_MODE_UNDEFINED,
                activityType = ACTIVITY_TYPE_UNDEFINED
        )
        val taskSurface = SurfaceControl()
        setUpMockDecorationForTask(task)

        onTaskChanging(task, taskSurface)
        verify(mockDesktopModeWindowDecorFactory, never()).create(
                mContext,
                mockDisplayController,
                mockTaskOrganizer,
                task,
                taskSurface,
                mockMainHandler,
                mockMainChoreographer,
                mockSyncQueue,
                mockRootTaskDisplayAreaOrganizer
        )

        task.setWindowingMode(WINDOWING_MODE_FREEFORM)
        task.setActivityType(ACTIVITY_TYPE_STANDARD)
        onTaskChanging(task, taskSurface)
        verify(mockDesktopModeWindowDecorFactory, times(1)).create(
                mContext,
                mockDisplayController,
                mockTaskOrganizer,
                task,
                taskSurface,
                mockMainHandler,
                mockMainChoreographer,
                mockSyncQueue,
                mockRootTaskDisplayAreaOrganizer
        )
    }

    @Test
    fun testCreateAndDisposeEventReceiver() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        setUpMockDecorationForTask(task)

        onTaskOpening(task)
        desktopModeWindowDecorViewModel.destroyWindowDecoration(task)

        verify(mockInputMonitorFactory).create(any(), any())
        verify(mockInputMonitor).dispose()
    }

    @Test
    fun testEventReceiversOnMultipleDisplays() {
        val secondaryDisplay = createVirtualDisplay() ?: return
        val secondaryDisplayId = secondaryDisplay.display.displayId
        val task = createTask(displayId = DEFAULT_DISPLAY, windowingMode = WINDOWING_MODE_FREEFORM)
        val secondTask = createTask(
                displayId = secondaryDisplayId,
                windowingMode = WINDOWING_MODE_FREEFORM
        )
        val thirdTask = createTask(
                displayId = secondaryDisplayId,
                windowingMode = WINDOWING_MODE_FREEFORM
        )
        setUpMockDecorationsForTasks(task, secondTask, thirdTask)

        onTaskOpening(task)
        onTaskOpening(secondTask)
        onTaskOpening(thirdTask)
        desktopModeWindowDecorViewModel.destroyWindowDecoration(thirdTask)
        desktopModeWindowDecorViewModel.destroyWindowDecoration(task)
        secondaryDisplay.release()

        verify(mockInputMonitorFactory, times(2)).create(any(), any())
        verify(mockInputMonitor, times(1)).dispose()
    }

    @Test
    fun testCaptionIsNotCreatedWhenKeyguardIsVisible() {
        val task = createTask(windowingMode = WINDOWING_MODE_FULLSCREEN, focused = true)
        val keyguardListenerCaptor = argumentCaptor<KeyguardChangeListener>()
        verify(mockShellController).addKeyguardChangeListener(keyguardListenerCaptor.capture())

        keyguardListenerCaptor.firstValue.onKeyguardVisibilityChanged(
                true /* visible */,
                true /* occluded */,
                false /* animatingDismiss */
        )
        onTaskOpening(task)

        task.setWindowingMode(WINDOWING_MODE_UNDEFINED)
        task.setWindowingMode(ACTIVITY_TYPE_UNDEFINED)
        onTaskChanging(task)

        verify(mockDesktopModeWindowDecorFactory, never())
                .create(any(), any(), any(), eq(task), any(), any(), any(), any(), any())
    }

    @Test
    fun testRelayoutRunsWhenStatusBarsInsetsSourceVisibilityChanges() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM, focused = true)
        val decoration = setUpMockDecorationForTask(task)

        onTaskOpening(task)

        // Add status bar insets source
        val insetsState = InsetsState()
        val statusBarInsetsSourceId = 0
        val statusBarInsetsSource = InsetsSource(statusBarInsetsSourceId, statusBars())
        statusBarInsetsSource.isVisible = false
        insetsState.addSource(statusBarInsetsSource)

        desktopModeOnInsetsChangedListener.insetsChanged(insetsState)

        // Verify relayout occurs when status bar inset visibility changes
        verify(decoration, times(1)).relayout(task)
    }

    @Test
    fun testRelayoutDoesNotRunWhenNonStatusBarsInsetsSourceVisibilityChanges() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM, focused = true)
        val decoration = setUpMockDecorationForTask(task)

        onTaskOpening(task)

        // Add navigation bar insets source
        val insetsState = InsetsState()
        val navigationBarInsetsSourceId = 1
        val navigationBarInsetsSource = InsetsSource(navigationBarInsetsSourceId, navigationBars())
        navigationBarInsetsSource.isVisible = false
        insetsState.addSource(navigationBarInsetsSource)

        desktopModeOnInsetsChangedListener.insetsChanged(insetsState)

        // Verify relayout does not occur when non-status bar inset changes visibility
        verify(decoration, never()).relayout(task)
    }

    @Test
    fun testRelayoutDoesNotRunWhenNonStatusBarsInsetSourceVisibilityDoesNotChange() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM, focused = true)
        val decoration = setUpMockDecorationForTask(task)

        onTaskOpening(task)

        // Add status bar insets source
        val insetsState = InsetsState()
        val statusBarInsetsSourceId = 0
        val statusBarInsetsSource = InsetsSource(statusBarInsetsSourceId, statusBars())
        statusBarInsetsSource.isVisible = false
        insetsState.addSource(statusBarInsetsSource)

        desktopModeOnInsetsChangedListener.insetsChanged(insetsState)
        desktopModeOnInsetsChangedListener.insetsChanged(insetsState)

        // Verify relayout runs only once when status bar inset visibility changes.
        verify(decoration, times(1)).relayout(task)
    }

    private fun onTaskOpening(task: RunningTaskInfo, leash: SurfaceControl = SurfaceControl()) {
        desktopModeWindowDecorViewModel.onTaskOpening(
                task,
                leash,
                SurfaceControl.Transaction(),
                SurfaceControl.Transaction()
        )
    }

    private fun onTaskChanging(task: RunningTaskInfo, leash: SurfaceControl = SurfaceControl()) {
        desktopModeWindowDecorViewModel.onTaskChanging(
                task,
                leash,
                SurfaceControl.Transaction(),
                SurfaceControl.Transaction()
        )
    }

    private fun createTask(
            displayId: Int = DEFAULT_DISPLAY,
            @WindowConfiguration.WindowingMode windowingMode: Int,
            activityType: Int = ACTIVITY_TYPE_STANDARD,
            focused: Boolean = true
    ): RunningTaskInfo {
        return TestRunningTaskInfoBuilder()
                .setDisplayId(displayId)
                .setWindowingMode(windowingMode)
                .setVisible(true)
                .setActivityType(activityType)
                .build().apply {
                    isFocused = focused
                }
    }

    private fun setUpMockDecorationForTask(task: RunningTaskInfo): DesktopModeWindowDecoration {
        val decoration = mock(DesktopModeWindowDecoration::class.java)
        whenever(mockDesktopModeWindowDecorFactory.create(
                any(), any(), any(), eq(task), any(), any(), any(), any(), any())
        ).thenReturn(decoration)
        decoration.mTaskInfo = task
        whenever(decoration.isFocused).thenReturn(task.isFocused)
        return decoration
    }

    private fun setUpMockDecorationsForTasks(vararg tasks: RunningTaskInfo) {
        tasks.forEach { setUpMockDecorationForTask(it) }
    }

    private fun createVirtualDisplay(): VirtualDisplay? {
        val surfaceView = SurfaceView(mContext)
        val dm = mContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.createVirtualDisplay(
                "testEventReceiversOnMultipleDisplays",
                /*width=*/ 400,
                /*height=*/ 400,
                /*densityDpi=*/320,
                surfaceView.holder.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
    }

    private fun RunningTaskInfo.setWindowingMode(@WindowConfiguration.WindowingMode mode: Int) {
        configuration.windowConfiguration.windowingMode = mode
    }

    private fun RunningTaskInfo.setActivityType(type: Int) {
        configuration.windowConfiguration.activityType = type
    }

    companion object {
        private const val TAG = "DesktopModeWindowDecorViewModelTests"
        private val STABLE_INSETS = Rect(0, 100, 0, 0)
    }
}

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
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW
import android.app.WindowConfiguration.WINDOWING_MODE_PINNED
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.app.WindowConfiguration.WindowingMode
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_MAIN
import android.graphics.Rect
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.net.Uri
import android.os.SystemClock
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.Display.DEFAULT_DISPLAY
import android.view.ISystemGestureExclusionListener
import android.view.InsetsSource
import android.view.InsetsState
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.View
import android.view.ViewRootImpl
import android.view.WindowInsets.Type.statusBars
import android.window.WindowContainerTransaction
import android.window.WindowContainerTransaction.HierarchyOp
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean
import com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.window.flags.Flags
import com.android.wm.shell.R
import com.android.wm.shell.desktopmode.common.ToggleTaskSizeInteraction
import com.android.wm.shell.desktopmode.DesktopImmersiveController
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.InputMethod
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.ResizeTrigger
import com.android.wm.shell.desktopmode.DesktopTasksController.SnapPosition
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.shared.desktopmode.DesktopModeTransitionSource
import com.android.wm.shell.splitscreen.SplitScreenController
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentCaptor.forClass
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.kotlin.KArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.util.function.Consumer


/**
 * Tests of [DesktopModeWindowDecorViewModel]
 * Usage: atest WMShellUnitTests:DesktopModeWindowDecorViewModelTests
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class DesktopModeWindowDecorViewModelTests : DesktopModeWindowDecorViewModelTestsBase() {

    @Before
    fun setUp() {
        mockitoSession =
            mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(DesktopModeStatus::class.java)
                .spyStatic(DragPositioningCallbackUtility::class.java)
                .startMocking()

        doReturn(true).`when` { DesktopModeStatus.isDesktopModeSupported(Mockito.any()) }
        doReturn(false).`when` { DesktopModeStatus.overridesShowAppHandle(Mockito.any()) }

        setUpCommon()
    }

    @Test
    fun testDeleteCaptionOnChangeTransitionWhenNecessary() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        val taskSurface = SurfaceControl()
        val decoration = setUpMockDecorationForTask(task)

        onTaskOpening(task, taskSurface)
        assertTrue(windowDecorByTaskIdSpy.contains(task.taskId))

        task.setWindowingMode(WINDOWING_MODE_UNDEFINED)
        task.setActivityType(ACTIVITY_TYPE_UNDEFINED)
        onTaskChanging(task, taskSurface)

        assertFalse(windowDecorByTaskIdSpy.contains(task.taskId))
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
        assertFalse(windowDecorByTaskIdSpy.contains(task.taskId))

        task.setWindowingMode(WINDOWING_MODE_FREEFORM)
        task.setActivityType(ACTIVITY_TYPE_STANDARD)
        onTaskChanging(task, taskSurface)
        assertTrue(windowDecorByTaskIdSpy.contains(task.taskId))
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_HANDLE_INPUT_FIX)
    fun testCreateAndDisposeEventReceiver() {
        val decor = createOpenTaskDecoration(windowingMode = WINDOWING_MODE_FREEFORM)
        desktopModeWindowDecorViewModel.destroyWindowDecoration(decor.mTaskInfo)

        verify(mockInputMonitorFactory).create(any(), any())
        verify(mockInputMonitor).dispose()
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_HANDLE_INPUT_FIX)
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
    fun testBackEventHasRightDisplayId() {
        val secondaryDisplay = createVirtualDisplay() ?: return
        val secondaryDisplayId = secondaryDisplay.display.displayId
        val task = createTask(
            displayId = secondaryDisplayId,
            windowingMode = WINDOWING_MODE_FREEFORM
        )
        val windowDecor = setUpMockDecorationForTask(task)

        onTaskOpening(task)
        val onClickListenerCaptor = argumentCaptor<View.OnClickListener>()
        verify(windowDecor).setCaptionListeners(
            onClickListenerCaptor.capture(), any(), any(), any())

        val onClickListener = onClickListenerCaptor.firstValue
        val view = mock(View::class.java)
        whenever(view.id).thenReturn(R.id.back_button)

        val inputManager = mock(InputManager::class.java)
        spyContext.addMockSystemService(InputManager::class.java, inputManager)

        desktopModeWindowDecorViewModel
                .setFreeformTaskTransitionStarter(mockFreeformTaskTransitionStarter)

        onClickListener.onClick(view)

        val eventCaptor = argumentCaptor<KeyEvent>()
        verify(inputManager, times(2)).injectInputEvent(eventCaptor.capture(), anyInt())

        assertEquals(secondaryDisplayId, eventCaptor.firstValue.displayId)
        assertEquals(secondaryDisplayId, eventCaptor.secondValue.displayId)
    }

    @Test
    fun testCloseButtonInFreeform_closeWindow() {
        val onClickListenerCaptor = forClass(View.OnClickListener::class.java)
                as ArgumentCaptor<View.OnClickListener>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onCaptionButtonClickListener = onClickListenerCaptor
        )

        val view = mock(View::class.java)
        whenever(view.id).thenReturn(R.id.close_window)

        desktopModeWindowDecorViewModel
            .setFreeformTaskTransitionStarter(mockFreeformTaskTransitionStarter)

        onClickListenerCaptor.value.onClick(view)

        val transactionCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(mockFreeformTaskTransitionStarter).startRemoveTransition(transactionCaptor.capture())
        val wct = transactionCaptor.firstValue

        assertEquals(1, wct.getHierarchyOps().size)
        assertEquals(HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_TASK,
                     wct.getHierarchyOps().get(0).getType())
        assertEquals(decor.mTaskInfo.token.asBinder(), wct.getHierarchyOps().get(0).getContainer())
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_MINIMIZE_BUTTON)
    fun testMinimizeButtonInFreefrom_minimizeWindow() {
        val onClickListenerCaptor = forClass(View.OnClickListener::class.java)
                as ArgumentCaptor<View.OnClickListener>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onCaptionButtonClickListener = onClickListenerCaptor
        )

        val view = mock(View::class.java)
        whenever(view.id).thenReturn(R.id.minimize_window)

        desktopModeWindowDecorViewModel
            .setFreeformTaskTransitionStarter(mockFreeformTaskTransitionStarter)

        onClickListenerCaptor.value.onClick(view)

        verify(mockDesktopTasksController).minimizeTask(decor.mTaskInfo)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODALS_POLICY)
    fun testDecorationIsNotCreatedForTopTranslucentActivities() {
        val task = createTask(windowingMode = WINDOWING_MODE_FULLSCREEN).apply {
            isActivityStackTransparent = true
            isTopActivityNoDisplay = false
            numActivities = 1
        }
        onTaskOpening(task)

        assertFalse(windowDecorByTaskIdSpy.contains(task.taskId))
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODALS_POLICY)
    fun testDecorationIsNotCreatedForSystemUIActivities() {
        // Set task as systemUI package
        val systemUIPackageName = context.resources.getString(
            com.android.internal.R.string.config_systemUi)
        val baseComponent = ComponentName(systemUIPackageName, /* class */ "")
        val task = createTask(windowingMode = WINDOWING_MODE_FULLSCREEN).apply {
            baseActivity = baseComponent
            isTopActivityNoDisplay = false
        }

        onTaskOpening(task)

        assertFalse(windowDecorByTaskIdSpy.contains(task.taskId))
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_IMMERSIVE_HANDLE_HIDING)
    fun testInsetsStateChanged_notifiesAllDecorsInDisplay() {
        val task1 = createTask(windowingMode = WINDOWING_MODE_FREEFORM, displayId = 1)
        val decoration1 = setUpMockDecorationForTask(task1)
        onTaskOpening(task1)
        val task2 = createTask(windowingMode = WINDOWING_MODE_FREEFORM, displayId = 2)
        val decoration2 = setUpMockDecorationForTask(task2)
        onTaskOpening(task2)
        val task3 = createTask(windowingMode = WINDOWING_MODE_FREEFORM, displayId = 2)
        val decoration3 = setUpMockDecorationForTask(task3)
        onTaskOpening(task3)

        // Add status bar insets source
        val insetsState = InsetsState().apply {
            addSource(InsetsSource(0 /* id */, statusBars()).apply {
                isVisible = false
            })
        }
        desktopModeOnInsetsChangedListener.insetsChanged(2 /* displayId */, insetsState)

        verify(decoration1, never()).onInsetsStateChanged(insetsState)
        verify(decoration2).onInsetsStateChanged(insetsState)
        verify(decoration3).onInsetsStateChanged(insetsState)
    }

    @Test
    fun testKeyguardState_notifiesAllDecors() {
        val decoration1 = createOpenTaskDecoration(windowingMode = WINDOWING_MODE_FREEFORM)
        val decoration2 = createOpenTaskDecoration(windowingMode = WINDOWING_MODE_FREEFORM)
        val decoration3 = createOpenTaskDecoration(windowingMode = WINDOWING_MODE_FREEFORM)

        desktopModeOnKeyguardChangedListener
            .onKeyguardVisibilityChanged(true /* visible */, true /* occluded */,
                false /* animatingDismiss */)

        verify(decoration1).onKeyguardStateChanged(true /* visible */, true /* occluded */)
        verify(decoration2).onKeyguardStateChanged(true /* visible */, true /* occluded */)
        verify(decoration3).onKeyguardStateChanged(true /* visible */, true /* occluded */)
    }

    @Test
    fun testDestroyWindowDecoration_closesBeforeCleanup() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        val decoration = setUpMockDecorationForTask(task)
        val inOrder = Mockito.inOrder(decoration, windowDecorByTaskIdSpy)

        onTaskOpening(task)
        desktopModeWindowDecorViewModel.destroyWindowDecoration(task)

        inOrder.verify(decoration).close()
        inOrder.verify(windowDecorByTaskIdSpy).remove(task.taskId)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    fun testWindowDecor_desktopModeUnsupportedOnDevice_deviceRestrictionsOverridden_decorCreated() {
        // Simulate enforce device restrictions system property overridden to false
        whenever(DesktopModeStatus.enforceDeviceRestrictions()).thenReturn(false)
        // Simulate device that doesn't support desktop mode
        doReturn(false).`when` { DesktopModeStatus.isDesktopModeSupported(any()) }

        val task = createTask(windowingMode = WINDOWING_MODE_FULLSCREEN)
        setUpMockDecorationsForTasks(task)

        onTaskOpening(task)
        assertTrue(windowDecorByTaskIdSpy.contains(task.taskId))
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    fun testWindowDecor_deviceSupportsDesktopMode_decorCreated() {
        // Simulate default enforce device restrictions system property
        whenever(DesktopModeStatus.enforceDeviceRestrictions()).thenReturn(true)

        val task = createTask(windowingMode = WINDOWING_MODE_FULLSCREEN)
        doReturn(true).`when` { DesktopModeStatus.isDesktopModeSupported(any()) }
        setUpMockDecorationsForTasks(task)

        onTaskOpening(task)
        assertTrue(windowDecorByTaskIdSpy.contains(task.taskId))
    }

    @Test
    fun testOnDecorMaximizedOrRestored_togglesTaskSize() {
        val maxOrRestoreListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onMaxOrRestoreListenerCaptor = maxOrRestoreListenerCaptor
        )

        maxOrRestoreListenerCaptor.value.invoke()

        verify(mockDesktopTasksController).toggleDesktopTaskSize(
            decor.mTaskInfo,
            ToggleTaskSizeInteraction(
                ToggleTaskSizeInteraction.Direction.MAXIMIZE,
                ToggleTaskSizeInteraction.Source.MAXIMIZE_MENU_TO_MAXIMIZE,
                InputMethod.UNKNOWN_INPUT_METHOD
            )
        )
    }

    @Test
    fun testOnDecorMaximizedOrRestored_closesMenus() {
        val maxOrRestoreListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onMaxOrRestoreListenerCaptor = maxOrRestoreListenerCaptor
        )

        maxOrRestoreListenerCaptor.value.invoke()

        verify(decor).closeHandleMenu()
        verify(decor).closeMaximizeMenu()
    }

    @Test
    fun testOnDecorSnappedLeft_snapResizes() {
        val onLeftSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onLeftSnapClickListenerCaptor = onLeftSnapClickListenerCaptor
        )

        onLeftSnapClickListenerCaptor.value.invoke()

        verify(mockDesktopTasksController).handleInstantSnapResizingTask(
            eq(decor.mTaskInfo),
            eq(SnapPosition.LEFT),
            eq(ResizeTrigger.SNAP_LEFT_MENU),
            eq(InputMethod.UNKNOWN_INPUT_METHOD),
            eq(decor)
        )
    }

    @Test
    fun testOnDecorSnappedLeft_closeMenus() {
        val onLeftSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onLeftSnapClickListenerCaptor = onLeftSnapClickListenerCaptor
        )

        onLeftSnapClickListenerCaptor.value.invoke()

        verify(decor).closeHandleMenu()
        verify(decor).closeMaximizeMenu()
    }

    @Test
    @DisableFlags(Flags.FLAG_DISABLE_NON_RESIZABLE_APP_SNAP_RESIZING)
    fun testOnSnapResizeLeft_nonResizable_decorSnappedLeft() {
        val onLeftSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onLeftSnapClickListenerCaptor = onLeftSnapClickListenerCaptor
        ).also { it.mTaskInfo.isResizeable = false }

        onLeftSnapClickListenerCaptor.value.invoke()

        verify(mockDesktopTasksController).handleInstantSnapResizingTask(
            eq(decor.mTaskInfo),
            eq(SnapPosition.LEFT),
            eq(ResizeTrigger.SNAP_LEFT_MENU),
            eq(InputMethod.UNKNOWN_INPUT_METHOD),
            eq(decor),
        )
    }

    @Test
    @EnableFlags(Flags.FLAG_DISABLE_NON_RESIZABLE_APP_SNAP_RESIZING)
    fun testOnSnapResizeLeft_nonResizable_decorNotSnapped() {
        val onLeftSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onLeftSnapClickListenerCaptor = onLeftSnapClickListenerCaptor
        ).also { it.mTaskInfo.isResizeable = false }

        val currentBounds = decor.mTaskInfo.configuration.windowConfiguration.bounds
        onLeftSnapClickListenerCaptor.value.invoke()

        verify(mockDesktopTasksController, never())
            .snapToHalfScreen(eq(decor.mTaskInfo), any(), eq(currentBounds), eq(SnapPosition.LEFT),
                eq(ResizeTrigger.MAXIMIZE_BUTTON),
                eq(InputMethod.UNKNOWN_INPUT_METHOD),
                eq(decor),
            )
    }

    @Test
    fun testOnDecorSnappedRight_snapResizes() {
        val onRightSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onRightSnapClickListenerCaptor = onRightSnapClickListenerCaptor
        )

        onRightSnapClickListenerCaptor.value.invoke()

        verify(mockDesktopTasksController).handleInstantSnapResizingTask(
            eq(decor.mTaskInfo),
            eq(SnapPosition.RIGHT),
            eq(ResizeTrigger.SNAP_RIGHT_MENU),
            eq(InputMethod.UNKNOWN_INPUT_METHOD),
            eq(decor),
        )
    }

    @Test
    fun testOnDecorSnappedRight_closeMenus() {
        val onRightSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onRightSnapClickListenerCaptor = onRightSnapClickListenerCaptor
        )

        onRightSnapClickListenerCaptor.value.invoke()

        verify(decor).closeHandleMenu()
        verify(decor).closeMaximizeMenu()
    }

    @Test
    @DisableFlags(Flags.FLAG_DISABLE_NON_RESIZABLE_APP_SNAP_RESIZING)
    fun testOnSnapResizeRight_nonResizable_decorSnappedRight() {
        val onRightSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onRightSnapClickListenerCaptor = onRightSnapClickListenerCaptor
        ).also { it.mTaskInfo.isResizeable = false }

        onRightSnapClickListenerCaptor.value.invoke()

        verify(mockDesktopTasksController).handleInstantSnapResizingTask(
            eq(decor.mTaskInfo),
            eq(SnapPosition.RIGHT),
            eq(ResizeTrigger.SNAP_RIGHT_MENU),
            eq(InputMethod.UNKNOWN_INPUT_METHOD),
            eq(decor),
        )
    }

    @Test
    @EnableFlags(Flags.FLAG_DISABLE_NON_RESIZABLE_APP_SNAP_RESIZING)
    fun testOnSnapResizeRight_nonResizable_decorNotSnapped() {
        val onRightSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onRightSnapClickListenerCaptor = onRightSnapClickListenerCaptor
        ).also { it.mTaskInfo.isResizeable = false }

        val currentBounds = decor.mTaskInfo.configuration.windowConfiguration.bounds
        onRightSnapClickListenerCaptor.value.invoke()

        verify(mockDesktopTasksController, never())
            .snapToHalfScreen(eq(decor.mTaskInfo), any(), eq(currentBounds), eq(SnapPosition.RIGHT),
                eq(ResizeTrigger.MAXIMIZE_BUTTON),
                eq(InputMethod.UNKNOWN_INPUT_METHOD),
                eq(decor),
        )
    }

    @Test
    fun testDecor_onClickToDesktop_movesToDesktopWithSource() {
        val toDesktopListenerCaptor = forClass(Consumer::class.java)
                as ArgumentCaptor<Consumer<DesktopModeTransitionSource>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FULLSCREEN,
            onToDesktopClickListenerCaptor = toDesktopListenerCaptor
        )

        toDesktopListenerCaptor.value.accept(DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON)

        verify(mockDesktopTasksController).moveTaskToDesktop(
            eq(decor.mTaskInfo.taskId),
            any(),
            eq(DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON),
            anyOrNull()
        )
    }

    @Test
    fun testDecor_onClickToDesktop_addsCaptionInsets() {
        val toDesktopListenerCaptor = forClass(Consumer::class.java)
                as ArgumentCaptor<Consumer<DesktopModeTransitionSource>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FULLSCREEN,
            onToDesktopClickListenerCaptor = toDesktopListenerCaptor
        )

        toDesktopListenerCaptor.value.accept(DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON)

        verify(decor).addCaptionInset(any())
    }

    @Test
    fun testDecor_onClickToDesktop_closesHandleMenu() {
        val toDesktopListenerCaptor = forClass(Consumer::class.java)
                    as ArgumentCaptor<Consumer<DesktopModeTransitionSource>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FULLSCREEN,
            onToDesktopClickListenerCaptor = toDesktopListenerCaptor
        )

        toDesktopListenerCaptor.value.accept(DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON)

        verify(decor).closeHandleMenu()
    }

    @Test
    fun testDecor_onClickToFullscreen_closesHandleMenu() {
        val toFullscreenListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onToFullscreenClickListenerCaptor = toFullscreenListenerCaptor
        )

        toFullscreenListenerCaptor.value.invoke()

        verify(decor).closeHandleMenu()
    }

    @Test
    fun testDecor_onClickToFullscreen_isFreeform_movesToFullscreen() {
        val toFullscreenListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onToFullscreenClickListenerCaptor = toFullscreenListenerCaptor
        )

        toFullscreenListenerCaptor.value.invoke()

        verify(mockDesktopTasksController).moveToFullscreen(
            decor.mTaskInfo.taskId,
            DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON
        )
    }

    @Test
    fun testDecor_onClickToFullscreen_isSplit_movesToFullscreen() {
        val toFullscreenListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_MULTI_WINDOW,
            onToFullscreenClickListenerCaptor = toFullscreenListenerCaptor
        )

        toFullscreenListenerCaptor.value.invoke()

        verify(mockSplitScreenController).moveTaskToFullscreen(
            decor.mTaskInfo.taskId,
            SplitScreenController.EXIT_REASON_DESKTOP_MODE
        )
    }

    @Test
    fun testDecor_onClickToSplitScreen_closesHandleMenu() {
        val toSplitScreenListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_MULTI_WINDOW,
            onToSplitScreenClickListenerCaptor = toSplitScreenListenerCaptor
        )

        toSplitScreenListenerCaptor.value.invoke()

        verify(decor).closeHandleMenu()
    }

    @Test
    fun testDecor_onClickToSplitScreen_requestsSplit() {
        val toSplitScreenListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_MULTI_WINDOW,
            onToSplitScreenClickListenerCaptor = toSplitScreenListenerCaptor
        )

        toSplitScreenListenerCaptor.value.invoke()

        verify(mockDesktopTasksController).requestSplit(decor.mTaskInfo, leftOrTop = false)
    }

    @Test
    fun testDecor_onClickToSplitScreen_disposesStatusBarInputLayer() {
        val toSplitScreenListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_MULTI_WINDOW,
            onToSplitScreenClickListenerCaptor = toSplitScreenListenerCaptor
        )

        toSplitScreenListenerCaptor.value.invoke()

        verify(decor).disposeStatusBarInputLayer()
    }

    @Test
    fun testDecor_onClickToOpenBrowser_closeMenus() {
        val openInBrowserListenerCaptor = forClass(Consumer::class.java)
                as ArgumentCaptor<Consumer<Intent>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FULLSCREEN,
            onOpenInBrowserClickListener = openInBrowserListenerCaptor
        )

        openInBrowserListenerCaptor.value.accept(Intent())

        verify(decor).closeHandleMenu()
        verify(decor).closeMaximizeMenu()
    }

    @Test
    fun testDecor_onClickToOpenBrowser_opensBrowser() {
        doNothing().whenever(spyContext).startActivity(any())
        val uri = Uri.parse("https://www.google.com")
        val intent = Intent(ACTION_MAIN, uri)
        val openInBrowserListenerCaptor = forClass(Consumer::class.java)
                as ArgumentCaptor<Consumer<Intent>>
        createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FULLSCREEN,
            onOpenInBrowserClickListener = openInBrowserListenerCaptor
        )

        openInBrowserListenerCaptor.value.accept(intent)

        verify(spyContext).startActivityAsUser(argThat { intent ->
            uri.equals(intent.data)
                    && intent.action == ACTION_MAIN
        }, eq(mockUserHandle))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
    fun testDecor_createWindowDecoration_setsAppHandleEducationTooltipClickCallbacks() {
        whenever(DesktopModeStatus.canEnterDesktopMode(any())).thenReturn(true)

        shellInit.init()

        verify(
            mockAppHandleEducationController,
            times(1)
        ).setAppHandleEducationTooltipCallbacks(any(), any())
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
    fun testDecor_invokeOpenHandleMenuCallback_openHandleMenu() {
        whenever(DesktopModeStatus.canEnterDesktopMode(any())).thenReturn(true)
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        val decor = setUpMockDecorationForTask(task)
        val openHandleMenuCallbackCaptor = argumentCaptor<(Int) -> Unit>()
        // Set task as gmail
        val gmailPackageName = "com.google.android.gm"
        val baseComponent = ComponentName(gmailPackageName, /* class */ "")
        task.baseActivity = baseComponent

        onTaskOpening(task)
        verify(
            mockAppHandleEducationController,
            times(1)
        ).setAppHandleEducationTooltipCallbacks(openHandleMenuCallbackCaptor.capture(), any())
        openHandleMenuCallbackCaptor.lastValue.invoke(task.taskId)
        bgExecutor.flushAll()
        testShellExecutor.flushAll()

        verify(decor, times(1)).createHandleMenu(anyBoolean())
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
    fun testDecor_openTaskWithFlagDisabled_doNotOpenHandleMenu() {
        whenever(DesktopModeStatus.canEnterDesktopMode(any())).thenReturn(true)
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        setUpMockDecorationForTask(task)
        val openHandleMenuCallbackCaptor = argumentCaptor<(Int) -> Unit>()
        // Set task as gmail
        val gmailPackageName = "com.google.android.gm"
        val baseComponent = ComponentName(gmailPackageName, /* class */ "")
        task.baseActivity = baseComponent

        onTaskOpening(task)
        verify(
            mockAppHandleEducationController,
            never()
        ).setAppHandleEducationTooltipCallbacks(openHandleMenuCallbackCaptor.capture(), any())
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
    fun testDecor_invokeOnToDesktopCallback_setsAppHandleEducationTooltipClickCallbacks() {
        whenever(DesktopModeStatus.canEnterDesktopMode(any())).thenReturn(true)
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        setUpMockDecorationsForTasks(task)
        onTaskOpening(task)
        val onToDesktopCallbackCaptor = argumentCaptor<(Int, DesktopModeTransitionSource) -> Unit>()

        verify(
            mockAppHandleEducationController,
            times(1)
        ).setAppHandleEducationTooltipCallbacks(any(), onToDesktopCallbackCaptor.capture())
        onToDesktopCallbackCaptor.lastValue.invoke(
            task.taskId,
            DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON
        )

        verify(mockDesktopTasksController, times(1))
            .moveTaskToDesktop(any(), any(), any(), anyOrNull())
    }

    @Test
    fun testOnDisplayRotation_tasksOutOfValidArea_taskBoundsUpdated() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        val secondTask =
            createTask(displayId = task.displayId, windowingMode = WINDOWING_MODE_FREEFORM)
        val thirdTask =
            createTask(displayId = task.displayId, windowingMode = WINDOWING_MODE_FREEFORM)

        doReturn(true).`when` {
            DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(any(), any())
        }
        setUpMockDecorationsForTasks(task, secondTask, thirdTask)

        onTaskOpening(task)
        onTaskOpening(secondTask)
        onTaskOpening(thirdTask)

        val wct = mock<WindowContainerTransaction>()

        displayChangingListener.onDisplayChange(
            task.displayId, Surface.ROTATION_0, Surface.ROTATION_90, null, wct
        )

        verify(wct).setBounds(eq(task.token), any())
        verify(wct).setBounds(eq(secondTask.token), any())
        verify(wct).setBounds(eq(thirdTask.token), any())
    }

    @Test
    fun testOnDisplayRotation_taskInValidArea_taskBoundsNotUpdated() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        val secondTask =
            createTask(displayId = task.displayId, windowingMode = WINDOWING_MODE_FREEFORM)
        val thirdTask =
            createTask(displayId = task.displayId, windowingMode = WINDOWING_MODE_FREEFORM)

        doReturn(false).`when` {
            DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(any(), any())
        }
        setUpMockDecorationsForTasks(task, secondTask, thirdTask)

        onTaskOpening(task)
        onTaskOpening(secondTask)
        onTaskOpening(thirdTask)

        val wct = mock<WindowContainerTransaction>()
        displayChangingListener.onDisplayChange(
            task.displayId, Surface.ROTATION_0, Surface.ROTATION_90, null, wct
        )

        verify(wct, never()).setBounds(eq(task.token), any())
        verify(wct, never()).setBounds(eq(secondTask.token), any())
        verify(wct, never()).setBounds(eq(thirdTask.token), any())
    }

    @Test
    fun testOnDisplayRotation_sameOrientationRotation_taskBoundsNotUpdated() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        val secondTask =
            createTask(displayId = task.displayId, windowingMode = WINDOWING_MODE_FREEFORM)
        val thirdTask =
            createTask(displayId = task.displayId, windowingMode = WINDOWING_MODE_FREEFORM)

        setUpMockDecorationsForTasks(task, secondTask, thirdTask)

        onTaskOpening(task)
        onTaskOpening(secondTask)
        onTaskOpening(thirdTask)

        val wct = mock<WindowContainerTransaction>()
        displayChangingListener.onDisplayChange(
            task.displayId, Surface.ROTATION_0, Surface.ROTATION_180, null, wct
        )

        verify(wct, never()).setBounds(eq(task.token), any())
        verify(wct, never()).setBounds(eq(secondTask.token), any())
        verify(wct, never()).setBounds(eq(thirdTask.token), any())
    }

    @Test
    fun testOnDisplayRotation_differentDisplayId_taskBoundsNotUpdated() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        val secondTask = createTask(displayId = -2, windowingMode = WINDOWING_MODE_FREEFORM)
        val thirdTask = createTask(displayId = -3, windowingMode = WINDOWING_MODE_FREEFORM)

        doReturn(true).`when` {
            DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(any(), any())
        }
        setUpMockDecorationsForTasks(task, secondTask, thirdTask)

        onTaskOpening(task)
        onTaskOpening(secondTask)
        onTaskOpening(thirdTask)

        val wct = mock<WindowContainerTransaction>()
        displayChangingListener.onDisplayChange(
            task.displayId, Surface.ROTATION_0, Surface.ROTATION_90, null, wct
        )

        verify(wct).setBounds(eq(task.token), any())
        verify(wct, never()).setBounds(eq(secondTask.token), any())
        verify(wct, never()).setBounds(eq(thirdTask.token), any())
    }

    @Test
    fun testOnDisplayRotation_nonFreeformTask_taskBoundsNotUpdated() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        val secondTask = createTask(displayId = -2, windowingMode = WINDOWING_MODE_FULLSCREEN)
        val thirdTask = createTask(displayId = -3, windowingMode = WINDOWING_MODE_PINNED)

        doReturn(true).`when` {
            DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(any(), any())
        }
        setUpMockDecorationsForTasks(task, secondTask, thirdTask)

        onTaskOpening(task)
        onTaskOpening(secondTask)
        onTaskOpening(thirdTask)

        val wct = mock<WindowContainerTransaction>()
        displayChangingListener.onDisplayChange(
            task.displayId, Surface.ROTATION_0, Surface.ROTATION_90, null, wct
        )

        verify(wct).setBounds(eq(task.token), any())
        verify(wct, never()).setBounds(eq(secondTask.token), any())
        verify(wct, never()).setBounds(eq(thirdTask.token), any())
    }

    @Test
    fun testCloseButtonInFreeform_closeWindow_ignoreMoveEventsWithoutBoundsChange() {
        val onClickListenerCaptor = forClass(View.OnClickListener::class.java)
                as ArgumentCaptor<View.OnClickListener>
        val onTouchListenerCaptor = forClass(View.OnTouchListener::class.java)
                as ArgumentCaptor<View.OnTouchListener>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onCaptionButtonClickListener = onClickListenerCaptor,
            onCaptionButtonTouchListener = onTouchListenerCaptor
        )

        whenever(mockTaskPositioner.onDragPositioningStart(any(), any(), any()))
            .thenReturn(INITIAL_BOUNDS)
        whenever(mockTaskPositioner.onDragPositioningMove(any(), any()))
            .thenReturn(INITIAL_BOUNDS)
        whenever(mockTaskPositioner.onDragPositioningEnd(any(), any()))
            .thenReturn(INITIAL_BOUNDS)

        val view = mock(View::class.java)
        whenever(view.id).thenReturn(R.id.close_window)
        val viewRootImpl = mock(ViewRootImpl::class.java)
        whenever(view.getViewRootImpl()).thenReturn(viewRootImpl)
        whenever(viewRootImpl.getInputToken()).thenReturn(null)

        desktopModeWindowDecorViewModel
            .setFreeformTaskTransitionStarter(mockFreeformTaskTransitionStarter)

        onTouchListenerCaptor.value.onTouch(view,
            MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            MotionEvent.ACTION_DOWN, /* x= */ 0f, /* y= */ 0f, /* metaState= */ 0))
        onTouchListenerCaptor.value.onTouch(view,
            MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            MotionEvent.ACTION_MOVE, /* x= */ 0f, /* y= */ 0f, /* metaState= */ 0))
        onTouchListenerCaptor.value.onTouch(view,
            MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP, /* x= */ 0f, /* y= */ 0f, /* metaState= */ 0))
        onClickListenerCaptor.value.onClick(view)

        val transactionCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(mockFreeformTaskTransitionStarter).startRemoveTransition(transactionCaptor.capture())
        val wct = transactionCaptor.firstValue

        assertEquals(1, wct.getHierarchyOps().size)
        assertEquals(HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_TASK,
                     wct.getHierarchyOps().get(0).getType())
        assertEquals(decor.mTaskInfo.token.asBinder(), wct.getHierarchyOps().get(0).getContainer())
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun testImmersiveButtonClick_entersImmersiveMode() {
        val onClickListenerCaptor = forClass(View.OnClickListener::class.java)
                as ArgumentCaptor<View.OnClickListener>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onCaptionButtonClickListener = onClickListenerCaptor,
            requestingImmersive = true,
        )
        val view = mock(View::class.java)
        whenever(view.id).thenReturn(R.id.maximize_window)
        whenever(mockDesktopRepository.isTaskInFullImmersiveState(decor.mTaskInfo.taskId))
            .thenReturn(false)

        onClickListenerCaptor.value.onClick(view)

        verify(mockDesktopImmersiveController).moveTaskToImmersive(decor.mTaskInfo)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun testImmersiveRestoreButtonClick_exitsImmersiveMode() {
        val onClickListenerCaptor = forClass(View.OnClickListener::class.java)
                as ArgumentCaptor<View.OnClickListener>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onCaptionButtonClickListener = onClickListenerCaptor,
            requestingImmersive = true,
        )
        val view = mock(View::class.java)
        whenever(view.id).thenReturn(R.id.maximize_window)
        whenever(mockDesktopRepository.isTaskInFullImmersiveState(decor.mTaskInfo.taskId))
            .thenReturn(true)

        onClickListenerCaptor.value.onClick(view)

        verify(mockDesktopImmersiveController).moveTaskToNonImmersive(
            decor.mTaskInfo,
            DesktopImmersiveController.ExitReason.USER_INTERACTION
        )
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun testMaximizeButtonClick_notRequestingImmersive_togglesDesktopTaskSize() {
        val onClickListenerCaptor = forClass(View.OnClickListener::class.java)
                as ArgumentCaptor<View.OnClickListener>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onCaptionButtonClickListener = onClickListenerCaptor,
            requestingImmersive = false,
        )
        val view = mock(View::class.java)
        whenever(view.id).thenReturn(R.id.maximize_window)

        onClickListenerCaptor.value.onClick(view)

        verify(mockDesktopTasksController)
            .toggleDesktopTaskSize(
                decor.mTaskInfo,
                ToggleTaskSizeInteraction(
                    ToggleTaskSizeInteraction.Direction.MAXIMIZE,
                    ToggleTaskSizeInteraction.Source.HEADER_BUTTON_TO_MAXIMIZE,
                    InputMethod.UNKNOWN_INPUT_METHOD
                )
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun testImmersiveMenuOptionClick_entersImmersiveMode() {
        val onImmersiveClickCaptor = argumentCaptor<() -> Unit>()
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onImmersiveOrRestoreListenerCaptor = onImmersiveClickCaptor,
            requestingImmersive = true,
        )
        whenever(mockDesktopRepository.isTaskInFullImmersiveState(decor.mTaskInfo.taskId))
            .thenReturn(false)

        onImmersiveClickCaptor.firstValue()

        verify(mockDesktopImmersiveController).moveTaskToImmersive(decor.mTaskInfo)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun testImmersiveClick_closesMaximizeMenu() {
        val onImmersiveClickCaptor = argumentCaptor<() -> Unit>()
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onImmersiveOrRestoreListenerCaptor = onImmersiveClickCaptor,
            requestingImmersive = true,
        )

        onImmersiveClickCaptor.firstValue()

        verify(decor).closeMaximizeMenu()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DISPLAY_FOCUS_IN_SHELL_TRANSITIONS)
    fun testOnTaskInfoChanged_enableShellTransitionsFlag() {
        val task = createTask(
            windowingMode = WINDOWING_MODE_FREEFORM
        )
        val taskSurface = SurfaceControl()
        val decoration = setUpMockDecorationForTask(task)

        onTaskOpening(task, taskSurface)
        assertTrue(windowDecorByTaskIdSpy.contains(task.taskId))

        decoration.mHasGlobalFocus = true
        desktopModeWindowDecorViewModel.onTaskInfoChanged(task)
        verify(decoration).relayout(eq(task), eq(true), anyOrNull())

        decoration.mHasGlobalFocus = false
        desktopModeWindowDecorViewModel.onTaskInfoChanged(task)
        verify(decoration).relayout(eq(task), eq(false), anyOrNull())
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_DISPLAY_FOCUS_IN_SHELL_TRANSITIONS)
    fun testOnTaskInfoChanged_disableShellTransitionsFlag() {
        val task = createTask(
            windowingMode = WINDOWING_MODE_FREEFORM
        )
        val taskSurface = SurfaceControl()
        val decoration = setUpMockDecorationForTask(task)

        onTaskOpening(task, taskSurface)
        assertTrue(windowDecorByTaskIdSpy.contains(task.taskId))

        task.isFocused = true
        desktopModeWindowDecorViewModel.onTaskInfoChanged(task)
        verify(decoration).relayout(eq(task), eq(true), anyOrNull())

        task.isFocused = false
        desktopModeWindowDecorViewModel.onTaskInfoChanged(task)
        verify(decoration).relayout(eq(task), eq(false), anyOrNull())
    }

    @Test
    fun testGestureExclusionChanged_updatesDecorations() {
        val captor = argumentCaptor<ISystemGestureExclusionListener>()
        verify(mockWindowManager)
            .registerSystemGestureExclusionListener(captor.capture(), eq(DEFAULT_DISPLAY))
        val task = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            displayId = DEFAULT_DISPLAY
        )
        val task2 = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            displayId = DEFAULT_DISPLAY
        )
        val newRegion = Region.obtain().apply {
            set(Rect(0, 0, 1600, 80))
        }

        captor.firstValue.onSystemGestureExclusionChanged(DEFAULT_DISPLAY, newRegion, newRegion)
        testShellExecutor.flushAll()

        verify(task).onExclusionRegionChanged(newRegion)
        verify(task2).onExclusionRegionChanged(newRegion)
    }

    @Test
    fun testGestureExclusionChanged_otherDisplay_skipsDecorationUpdate() {
        val captor = argumentCaptor<ISystemGestureExclusionListener>()
        verify(mockWindowManager)
            .registerSystemGestureExclusionListener(captor.capture(), eq(DEFAULT_DISPLAY))
        val task = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            displayId = DEFAULT_DISPLAY
        )
        val task2 = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            displayId = 2
        )
        val newRegion = Region.obtain().apply {
            set(Rect(0, 0, 1600, 80))
        }

        captor.firstValue.onSystemGestureExclusionChanged(DEFAULT_DISPLAY, newRegion, newRegion)
        testShellExecutor.flushAll()

        verify(task).onExclusionRegionChanged(newRegion)
        verify(task2, never()).onExclusionRegionChanged(newRegion)
    }

    private fun createOpenTaskDecoration(
        @WindowingMode windowingMode: Int,
        taskSurface: SurfaceControl = SurfaceControl(),
        requestingImmersive: Boolean = false,
        displayId: Int = DEFAULT_DISPLAY,
        onMaxOrRestoreListenerCaptor: ArgumentCaptor<Function0<Unit>> =
            forClass(Function0::class.java) as ArgumentCaptor<Function0<Unit>>,
        onImmersiveOrRestoreListenerCaptor: KArgumentCaptor<() -> Unit> =
            argumentCaptor<() -> Unit>(),
        onLeftSnapClickListenerCaptor: ArgumentCaptor<Function0<Unit>> =
            forClass(Function0::class.java) as ArgumentCaptor<Function0<Unit>>,
        onRightSnapClickListenerCaptor: ArgumentCaptor<Function0<Unit>> =
            forClass(Function0::class.java) as ArgumentCaptor<Function0<Unit>>,
        onToDesktopClickListenerCaptor: ArgumentCaptor<Consumer<DesktopModeTransitionSource>> =
            forClass(Consumer::class.java) as ArgumentCaptor<Consumer<DesktopModeTransitionSource>>,
        onToFullscreenClickListenerCaptor: ArgumentCaptor<Function0<Unit>> =
            forClass(Function0::class.java) as ArgumentCaptor<Function0<Unit>>,
        onToSplitScreenClickListenerCaptor: ArgumentCaptor<Function0<Unit>> =
            forClass(Function0::class.java) as ArgumentCaptor<Function0<Unit>>,
        onOpenInBrowserClickListener: ArgumentCaptor<Consumer<Intent>> =
            forClass(Consumer::class.java) as ArgumentCaptor<Consumer<Intent>>,
        onCaptionButtonClickListener: ArgumentCaptor<View.OnClickListener> =
            forClass(View.OnClickListener::class.java) as ArgumentCaptor<View.OnClickListener>,
        onCaptionButtonTouchListener: ArgumentCaptor<View.OnTouchListener> =
            forClass(View.OnTouchListener::class.java) as ArgumentCaptor<View.OnTouchListener>
    ): DesktopModeWindowDecoration {
        val decor = setUpMockDecorationForTask(createTask(
            windowingMode = windowingMode,
            displayId = displayId,
            requestingImmersive = requestingImmersive
        ))
        onTaskOpening(decor.mTaskInfo, taskSurface)
        verify(decor).setOnMaximizeOrRestoreClickListener(onMaxOrRestoreListenerCaptor.capture())
        verify(decor)
            .setOnImmersiveOrRestoreClickListener(onImmersiveOrRestoreListenerCaptor.capture())
        verify(decor).setOnLeftSnapClickListener(onLeftSnapClickListenerCaptor.capture())
        verify(decor).setOnRightSnapClickListener(onRightSnapClickListenerCaptor.capture())
        verify(decor).setOnToDesktopClickListener(onToDesktopClickListenerCaptor.capture())
        verify(decor).setOnToFullscreenClickListener(onToFullscreenClickListenerCaptor.capture())
        verify(decor).setOnToSplitScreenClickListener(onToSplitScreenClickListenerCaptor.capture())
        verify(decor).setOpenInBrowserClickListener(onOpenInBrowserClickListener.capture())
        verify(decor).setCaptionListeners(
                onCaptionButtonClickListener.capture(), onCaptionButtonTouchListener.capture(),
                any(), any())
        return decor
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
                /*densityDpi=*/ 320,
                surfaceView.holder.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
    }
}

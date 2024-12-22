package com.android.wm.shell.desktopmode

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW
import android.app.WindowConfiguration.WindowingMode
import android.graphics.PointF
import android.os.IBinder
import android.os.SystemProperties
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_OPEN
import android.window.TransitionInfo
import android.window.TransitionInfo.FLAG_IS_WALLPAPER
import android.window.WindowContainerTransaction
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_HOLD
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_RELEASE
import com.android.internal.jank.InteractionJankMonitor
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT
import com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TRANSIT_DESKTOP_MODE_CANCEL_DRAG_TO_DESKTOP
import com.android.wm.shell.transition.Transitions.TRANSIT_DESKTOP_MODE_END_DRAG_TO_DESKTOP
import com.android.wm.shell.transition.Transitions.TRANSIT_DESKTOP_MODE_START_DRAG_TO_DESKTOP
import com.android.wm.shell.windowdecor.MoveToDesktopAnimator
import java.util.function.Supplier
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.MockitoSession
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyZeroInteractions
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

/** Tests of [DragToDesktopTransitionHandler]. */
@SmallTest
@RunWithLooper
@RunWith(AndroidTestingRunner::class)
class DragToDesktopTransitionHandlerTest : ShellTestCase() {

    @Mock private lateinit var transitions: Transitions
    @Mock private lateinit var taskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer
    @Mock private lateinit var splitScreenController: SplitScreenController
    @Mock private lateinit var dragAnimator: MoveToDesktopAnimator
    @Mock private lateinit var mockInteractionJankMonitor: InteractionJankMonitor
    @Mock private lateinit var draggedTaskLeash: SurfaceControl
    @Mock private lateinit var homeTaskLeash: SurfaceControl

    private val transactionSupplier = Supplier { mock<SurfaceControl.Transaction>() }

    private lateinit var defaultHandler: DragToDesktopTransitionHandler
    private lateinit var springHandler: SpringDragToDesktopTransitionHandler
    private lateinit var mockitoSession: MockitoSession

    @Before
    fun setUp() {
        defaultHandler =
            DefaultDragToDesktopTransitionHandler(
                    context,
                    transitions,
                    taskDisplayAreaOrganizer,
                    mockInteractionJankMonitor,
                    transactionSupplier,
                )
                .apply { setSplitScreenController(splitScreenController) }
        springHandler =
            SpringDragToDesktopTransitionHandler(
                    context,
                    transitions,
                    taskDisplayAreaOrganizer,
                    mockInteractionJankMonitor,
                    transactionSupplier,
                )
                .apply { setSplitScreenController(splitScreenController) }
        mockitoSession =
            ExtendedMockito.mockitoSession()
                .strictness(Strictness.LENIENT)
                .mockStatic(SystemProperties::class.java)
                .startMocking()
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
    }

    @Test
    fun startDragToDesktop_animateDragWhenReady() {
        val task = createTask()
        // Simulate transition is started.
        val transition = startDragToDesktopTransition(defaultHandler, task, dragAnimator)

        // Now it's ready to animate.
        defaultHandler.startAnimation(
            transition = transition,
            info =
                createTransitionInfo(
                    type = TRANSIT_DESKTOP_MODE_START_DRAG_TO_DESKTOP,
                    draggedTask = task
                ),
            startTransaction = mock(),
            finishTransaction = mock(),
            finishCallback = {}
        )

        verify(dragAnimator).startAnimation()
    }

    @Test
    fun startDragToDesktop_cancelledBeforeReady_startCancelTransition() {
        performEarlyCancel(
            defaultHandler,
            DragToDesktopTransitionHandler.CancelState.STANDARD_CANCEL
        )
        verify(transitions)
            .startTransition(
                eq(TRANSIT_DESKTOP_MODE_CANCEL_DRAG_TO_DESKTOP),
                any(),
                eq(defaultHandler)
            )
    }

    @Test
    fun startDragToDesktop_cancelledBeforeReady_verifySplitLeftCancel() {
        performEarlyCancel(
            defaultHandler,
            DragToDesktopTransitionHandler.CancelState.CANCEL_SPLIT_LEFT
        )
        verify(splitScreenController)
            .requestEnterSplitSelect(any(), any(), eq(SPLIT_POSITION_TOP_OR_LEFT), any())
    }

    @Test
    fun startDragToDesktop_cancelledBeforeReady_verifySplitRightCancel() {
        performEarlyCancel(
            defaultHandler,
            DragToDesktopTransitionHandler.CancelState.CANCEL_SPLIT_RIGHT
        )
        verify(splitScreenController)
            .requestEnterSplitSelect(any(), any(), eq(SPLIT_POSITION_BOTTOM_OR_RIGHT), any())
    }

    @Test
    fun startDragToDesktop_aborted_finishDropped() {
        val task = createTask()
        // Simulate transition is started.
        val transition = startDragToDesktopTransition(defaultHandler, task, dragAnimator)
        // But the transition was aborted.
        defaultHandler.onTransitionConsumed(transition, aborted = true, mock())

        // Attempt to finish the failed drag start.
        defaultHandler.finishDragToDesktopTransition(WindowContainerTransaction())

        // Should not be attempted and state should be reset.
        verify(transitions, never())
            .startTransition(eq(TRANSIT_DESKTOP_MODE_END_DRAG_TO_DESKTOP), any(), any())
        assertFalse(defaultHandler.inProgress)
    }

    @Test
    fun startDragToDesktop_aborted_cancelDropped() {
        val task = createTask()
        // Simulate transition is started.
        val transition = startDragToDesktopTransition(defaultHandler, task, dragAnimator)
        // But the transition was aborted.
        defaultHandler.onTransitionConsumed(transition, aborted = true, mock())

        // Attempt to finish the failed drag start.
        defaultHandler.cancelDragToDesktopTransition(
            DragToDesktopTransitionHandler.CancelState.STANDARD_CANCEL
        )

        // Should not be attempted and state should be reset.
        assertFalse(defaultHandler.inProgress)
    }

    @Test
    fun startDragToDesktop_anotherTransitionInProgress_startDropped() {
        val task = createTask()

        // Simulate attempt to start two drag to desktop transitions.
        startDragToDesktopTransition(defaultHandler, task, dragAnimator)
        startDragToDesktopTransition(defaultHandler, task, dragAnimator)

        // Verify transition only started once.
        verify(transitions, times(1))
            .startTransition(
                eq(TRANSIT_DESKTOP_MODE_START_DRAG_TO_DESKTOP),
                any(),
                eq(defaultHandler)
            )
    }

    @Test
    fun isHomeChange_withoutTaskInfo_returnsFalse() {
        val change =
            TransitionInfo.Change(mock(), homeTaskLeash).apply {
                parent = null
                taskInfo = null
            }

        assertFalse(defaultHandler.isHomeChange(change))
        assertFalse(springHandler.isHomeChange(change))
    }

    @Test
    fun isHomeChange_withStandardActivityTaskInfo_returnsFalse() {
        val change =
            TransitionInfo.Change(mock(), homeTaskLeash).apply {
                parent = null
                taskInfo =
                    TestRunningTaskInfoBuilder().setActivityType(ACTIVITY_TYPE_STANDARD).build()
            }

        assertFalse(defaultHandler.isHomeChange(change))
        assertFalse(springHandler.isHomeChange(change))
    }

    @Test
    fun isHomeChange_withHomeActivityTaskInfo_returnsTrue() {
        val change =
            TransitionInfo.Change(mock(), homeTaskLeash).apply {
                parent = null
                taskInfo = TestRunningTaskInfoBuilder().setActivityType(ACTIVITY_TYPE_HOME).build()
            }

        assertTrue(defaultHandler.isHomeChange(change))
        assertTrue(springHandler.isHomeChange(change))
    }

    @Test
    fun isHomeChange_withSingleTranslucentHomeActivityTaskInfo_returnsFalse() {
        val change =
            TransitionInfo.Change(mock(), homeTaskLeash).apply {
                parent = null
                taskInfo =
                    TestRunningTaskInfoBuilder()
                        .setActivityType(ACTIVITY_TYPE_HOME)
                        .setTopActivityTransparent(true)
                        .setNumActivities(1)
                        .build()
            }

        assertFalse(defaultHandler.isHomeChange(change))
        assertFalse(springHandler.isHomeChange(change))
    }

    @Test
    fun cancelDragToDesktop_startWasReady_cancel() {
        startDrag(defaultHandler)

        // Then user cancelled after it had already started.
        defaultHandler.cancelDragToDesktopTransition(
            DragToDesktopTransitionHandler.CancelState.STANDARD_CANCEL
        )

        // Cancel animation should run since it had already started.
        verify(dragAnimator).cancelAnimator()
    }

    @Test
    fun cancelDragToDesktop_splitLeftCancelType_splitRequested() {
        startDrag(defaultHandler)

        // Then user cancelled it, requesting split.
        defaultHandler.cancelDragToDesktopTransition(
            DragToDesktopTransitionHandler.CancelState.CANCEL_SPLIT_LEFT
        )

        // Verify the request went through split controller.
        verify(splitScreenController)
            .requestEnterSplitSelect(any(), any(), eq(SPLIT_POSITION_TOP_OR_LEFT), any())
    }

    @Test
    fun cancelDragToDesktop_splitRightCancelType_splitRequested() {
        startDrag(defaultHandler)

        // Then user cancelled it, requesting split.
        defaultHandler.cancelDragToDesktopTransition(
            DragToDesktopTransitionHandler.CancelState.CANCEL_SPLIT_RIGHT
        )

        // Verify the request went through split controller.
        verify(splitScreenController)
            .requestEnterSplitSelect(any(), any(), eq(SPLIT_POSITION_BOTTOM_OR_RIGHT), any())
    }

    @Test
    fun cancelDragToDesktop_startWasNotReady_animateCancel() {
        val task = createTask()
        // Simulate transition is started and is ready to animate.
        startDragToDesktopTransition(defaultHandler, task, dragAnimator)

        // Then user cancelled before the transition was ready and animated.
        defaultHandler.cancelDragToDesktopTransition(
            DragToDesktopTransitionHandler.CancelState.STANDARD_CANCEL
        )

        // No need to animate the cancel since the start animation couldn't even start.
        verifyZeroInteractions(dragAnimator)
    }

    @Test
    fun cancelDragToDesktop_transitionNotInProgress_dropCancel() {
        // Then cancel is called before the transition was started.
        defaultHandler.cancelDragToDesktopTransition(
            DragToDesktopTransitionHandler.CancelState.STANDARD_CANCEL
        )

        // Verify cancel is dropped.
        verify(transitions, never())
            .startTransition(
                eq(TRANSIT_DESKTOP_MODE_CANCEL_DRAG_TO_DESKTOP),
                any(),
                eq(defaultHandler)
            )
    }

    @Test
    fun finishDragToDesktop_transitionNotInProgress_dropFinish() {
        // Then finish is called before the transition was started.
        defaultHandler.finishDragToDesktopTransition(WindowContainerTransaction())

        // Verify finish is dropped.
        verify(transitions, never())
            .startTransition(
                eq(TRANSIT_DESKTOP_MODE_END_DRAG_TO_DESKTOP),
                any(),
                eq(defaultHandler)
            )
    }

    @Test
    fun mergeAnimation_otherTransition_doesNotMerge() {
        val transaction = mock<SurfaceControl.Transaction>()
        val finishCallback = mock<Transitions.TransitionFinishCallback>()
        val task = createTask()

        startDrag(defaultHandler, task)
        defaultHandler.mergeAnimation(
            transition = mock(),
            info = createTransitionInfo(type = TRANSIT_OPEN, draggedTask = task),
            t = transaction,
            mergeTarget = mock(),
            finishCallback = finishCallback
        )

        // Should NOT have any transaction changes
        verifyZeroInteractions(transaction)
        // Should NOT merge animation
        verify(finishCallback, never()).onTransitionFinished(any())
    }

    @Test
    fun mergeAnimation_endTransition_mergesAnimation() {
        val playingFinishTransaction = mock<SurfaceControl.Transaction>()
        val mergedStartTransaction = mock<SurfaceControl.Transaction>()
        val finishCallback = mock<Transitions.TransitionFinishCallback>()
        val task = createTask()
        val startTransition =
            startDrag(defaultHandler, task, finishTransaction = playingFinishTransaction)
        defaultHandler.onTaskResizeAnimationListener = mock()

        defaultHandler.mergeAnimation(
            transition = mock(),
            info =
                createTransitionInfo(
                    type = TRANSIT_DESKTOP_MODE_END_DRAG_TO_DESKTOP,
                    draggedTask = task
                ),
            t = mergedStartTransaction,
            mergeTarget = startTransition,
            finishCallback = finishCallback
        )

        // Should show dragged task layer in start and finish transaction
        verify(mergedStartTransaction).show(draggedTaskLeash)
        verify(playingFinishTransaction).show(draggedTaskLeash)
        // Should update the dragged task layer
        verify(mergedStartTransaction).setLayer(eq(draggedTaskLeash), anyInt())
        // Should merge animation
        verify(finishCallback).onTransitionFinished(null)
    }

    @Test
    fun mergeAnimation_endTransition_springHandler_hidesHome() {
        whenever(dragAnimator.computeCurrentVelocity()).thenReturn(PointF())
        val playingFinishTransaction = mock<SurfaceControl.Transaction>()
        val mergedStartTransaction = mock<SurfaceControl.Transaction>()
        val finishCallback = mock<Transitions.TransitionFinishCallback>()
        val task = createTask()
        val startTransition =
            startDrag(springHandler, task, finishTransaction = playingFinishTransaction)
        springHandler.onTaskResizeAnimationListener = mock()

        springHandler.mergeAnimation(
            transition = mock(),
            info =
                createTransitionInfo(
                    type = TRANSIT_DESKTOP_MODE_END_DRAG_TO_DESKTOP,
                    draggedTask = task
                ),
            t = mergedStartTransaction,
            mergeTarget = startTransition,
            finishCallback = finishCallback
        )

        // Should show dragged task layer in start and finish transaction
        verify(mergedStartTransaction).show(draggedTaskLeash)
        verify(playingFinishTransaction).show(draggedTaskLeash)
        // Should update the dragged task layer
        verify(mergedStartTransaction).setLayer(eq(draggedTaskLeash), anyInt())
        // Should hide home task leash in finish transaction
        verify(playingFinishTransaction).hide(homeTaskLeash)
        // Should merge animation
        verify(finishCallback).onTransitionFinished(null)
    }

    @Test
    fun propertyValue_returnsSystemPropertyValue() {
        val name = "property_name"
        val value = 10f

        whenever(SystemProperties.getInt(eq(systemPropertiesKey(name)), anyInt()))
            .thenReturn(value.toInt())

        assertEquals(
            "Expects to return system properties stored value",
            /* expected= */ value,
            /* actual= */ SpringDragToDesktopTransitionHandler.propertyValue(name)
        )
    }

    @Test
    fun propertyValue_withScale_returnsScaledSystemPropertyValue() {
        val name = "property_name"
        val value = 10f
        val scale = 100f

        whenever(SystemProperties.getInt(eq(systemPropertiesKey(name)), anyInt()))
            .thenReturn(value.toInt())

        assertEquals(
            "Expects to return scaled system properties stored value",
            /* expected= */ value / scale,
            /* actual= */ SpringDragToDesktopTransitionHandler.propertyValue(name, scale = scale)
        )
    }

    @Test
    fun propertyValue_notSet_returnsDefaultValue() {
        val name = "property_name"
        val defaultValue = 50f

        whenever(SystemProperties.getInt(eq(systemPropertiesKey(name)), eq(defaultValue.toInt())))
            .thenReturn(defaultValue.toInt())

        assertEquals(
            "Expects to return the default value",
            /* expected= */ defaultValue,
            /* actual= */ SpringDragToDesktopTransitionHandler.propertyValue(
                name,
                default = defaultValue
            )
        )
    }

    @Test
    fun propertyValue_withScaleNotSet_returnsDefaultValue() {
        val name = "property_name"
        val defaultValue = 0.5f
        val scale = 100f
        // Default value is multiplied when provided as a default value for [SystemProperties]
        val scaledDefault = (defaultValue * scale).toInt()

        whenever(SystemProperties.getInt(eq(systemPropertiesKey(name)), eq(scaledDefault)))
            .thenReturn(scaledDefault)

        assertEquals(
            "Expects to return the default value",
            /* expected= */ defaultValue,
            /* actual= */ SpringDragToDesktopTransitionHandler.propertyValue(
                name,
                default = defaultValue,
                scale = scale
            )
        )
    }

    @Test
    fun startDragToDesktop_aborted_logsDragHoldCancelled() {
        val transition = startDragToDesktopTransition(defaultHandler, createTask(), dragAnimator)

        defaultHandler.onTransitionConsumed(transition, aborted = true, mock())

        verify(mockInteractionJankMonitor).cancel(eq(CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_HOLD))
        verify(mockInteractionJankMonitor, times(0)).cancel(
            eq(CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_RELEASE))
    }

    @Test
    fun mergeEndDragToDesktop_aborted_logsDragReleaseCancelled() {
        val task = createTask()
        val startTransition = startDrag(defaultHandler, task)
        val endTransition = mock<IBinder>()
        defaultHandler.onTaskResizeAnimationListener = mock()
        defaultHandler.mergeAnimation(
            transition = endTransition,
            info = createTransitionInfo(
                type = TRANSIT_DESKTOP_MODE_END_DRAG_TO_DESKTOP,
                draggedTask = task
            ),
            t = mock<SurfaceControl.Transaction>(),
            mergeTarget = startTransition,
            finishCallback = mock<Transitions.TransitionFinishCallback>()
        )

        defaultHandler.onTransitionConsumed(endTransition, aborted = true, mock())

        verify(mockInteractionJankMonitor)
            .cancel(eq(CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_RELEASE))
        verify(mockInteractionJankMonitor, times(0))
            .cancel(eq(CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_HOLD))
    }

    private fun startDrag(
        handler: DragToDesktopTransitionHandler,
        task: RunningTaskInfo = createTask(),
        finishTransaction: SurfaceControl.Transaction = mock()
    ): IBinder {
        whenever(dragAnimator.position).thenReturn(PointF())
        // Simulate transition is started and is ready to animate.
        val transition = startDragToDesktopTransition(handler, task, dragAnimator)
        handler.startAnimation(
            transition = transition,
            info =
                createTransitionInfo(
                    type = TRANSIT_DESKTOP_MODE_START_DRAG_TO_DESKTOP,
                    draggedTask = task
                ),
            startTransaction = mock(),
            finishTransaction = finishTransaction,
            finishCallback = {}
        )
        return transition
    }

    private fun startDragToDesktopTransition(
        handler: DragToDesktopTransitionHandler,
        task: RunningTaskInfo,
        dragAnimator: MoveToDesktopAnimator
    ): IBinder {
        val token = mock<IBinder>()
        whenever(
                transitions.startTransition(
                    eq(TRANSIT_DESKTOP_MODE_START_DRAG_TO_DESKTOP),
                    any(),
                    eq(handler)
                )
            )
            .thenReturn(token)
        handler.startDragToDesktopTransition(task.taskId, dragAnimator)
        return token
    }

    private fun performEarlyCancel(
        handler: DragToDesktopTransitionHandler,
        cancelState: DragToDesktopTransitionHandler.CancelState
    ) {
        val task = createTask()
        // Simulate transition is started and is ready to animate.
        val transition = startDragToDesktopTransition(handler, task, dragAnimator)

        handler.cancelDragToDesktopTransition(cancelState)

        handler.startAnimation(
            transition = transition,
            info =
                createTransitionInfo(
                    type = TRANSIT_DESKTOP_MODE_START_DRAG_TO_DESKTOP,
                    draggedTask = task
                ),
            startTransaction = mock(),
            finishTransaction = mock(),
            finishCallback = {}
        )

        // Don't even animate the "drag" since it was already cancelled.
        verify(dragAnimator, never()).startAnimation()
    }

    private fun createTask(
        @WindowingMode windowingMode: Int = WINDOWING_MODE_FULLSCREEN,
        isHome: Boolean = false,
    ): RunningTaskInfo {
        return TestRunningTaskInfoBuilder()
            .setActivityType(if (isHome) ACTIVITY_TYPE_HOME else ACTIVITY_TYPE_STANDARD)
            .setWindowingMode(windowingMode)
            .build()
            .also {
                whenever(splitScreenController.isTaskInSplitScreen(it.taskId))
                    .thenReturn(windowingMode == WINDOWING_MODE_MULTI_WINDOW)
            }
    }

    private fun createTransitionInfo(type: Int, draggedTask: RunningTaskInfo): TransitionInfo {
        return TransitionInfo(type, 0 /* flags */).apply {
            addChange( // Home.
                TransitionInfo.Change(mock(), homeTaskLeash).apply {
                    parent = null
                    taskInfo =
                        TestRunningTaskInfoBuilder().setActivityType(ACTIVITY_TYPE_HOME).build()
                    flags = flags or FLAG_IS_WALLPAPER
                }
            )
            addChange( // Dragged Task.
                TransitionInfo.Change(mock(), draggedTaskLeash).apply {
                    parent = null
                    taskInfo = draggedTask
                }
            )
            addChange( // Wallpaper.
                TransitionInfo.Change(mock(), mock()).apply {
                    parent = null
                    taskInfo = null
                    flags = flags or FLAG_IS_WALLPAPER
                }
            )
        }
    }

    private fun systemPropertiesKey(name: String) =
        "${SpringDragToDesktopTransitionHandler.SYSTEM_PROPERTIES_GROUP}.$name"
}

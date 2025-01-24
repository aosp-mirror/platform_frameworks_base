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

package com.android.wm.shell.automotive

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT
import android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.graphics.Rect
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.testing.AndroidTestingRunner
import android.view.SurfaceControl
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT
import androidx.test.filters.SmallTest
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTaskOrganizer.TaskListener
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.TransitionInfoBuilder
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TransitionFinishCallback
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.Mockito.`when` as whenever


@SmallTest
@RunWith(AndroidTestingRunner::class)
class AutoTaskStackControllerImplTest : ShellTestCase() {

    @Mock
    lateinit var taskOrganizer: ShellTaskOrganizer

    @Mock
    lateinit var shellMainThread: ShellExecutor

    @Mock
    lateinit var transitions: Transitions

    @Mock
    lateinit var shellInit: ShellInit

    @Mock
    lateinit var rootTdaOrganizer: RootTaskDisplayAreaOrganizer

    @Mock
    lateinit var rootTaskStackListener: RootTaskStackListener

    var mMainThreadHandler: Handler? = null


    private lateinit var controller: AutoTaskStackControllerImpl
    private val displayId = 0
    private val delegate = TestAutoTaskStackTransitionHandlerDelegate()

    class TestAutoTaskStackTransitionHandlerDelegate :
        AutoTaskStackTransitionHandlerDelegate {
        var lastStartTransaction: SurfaceControl.Transaction? = null
        var lastFinishTransaction: SurfaceControl.Transaction? = null
        var lastTaskStackStates: Map<Int, AutoTaskStackState>? = null
        var handleRequestReturn: AutoTaskStackTransaction? = null
        var play = true

        override fun handleRequest(
            transition: IBinder,
            request: TransitionRequestInfo
        ): AutoTaskStackTransaction? {
            return handleRequestReturn
        }

        override fun startAnimation(
            transition: IBinder,
            changedTaskStacks: Map<Int, AutoTaskStackState>,
            info: TransitionInfo,
            startTransaction: SurfaceControl.Transaction,
            finishTransaction: SurfaceControl.Transaction,
            finishCallback: TransitionFinishCallback
        ): Boolean {
            lastStartTransaction = startTransaction
            lastFinishTransaction = finishTransaction
            lastTaskStackStates = changedTaskStacks
            return play
        }

        override fun onTransitionConsumed(
            transition: IBinder,
            requestedTaskStacks: Map<Int, AutoTaskStackState>,
            aborted: Boolean,
            finishTransaction: SurfaceControl.Transaction?
        ) {
        }

        override fun mergeAnimation(
            transition: IBinder,
            changedTaskStacks: Map<Int, AutoTaskStackState>,
            info: TransitionInfo,
            surfaceTransaction: SurfaceControl.Transaction,
            mergeTarget: IBinder,
            finishCallback: TransitionFinishCallback
        ) {
        }
    }

    private fun setupRootTask(
        taskId: Int,
        leash: SurfaceControl = mock(SurfaceControl::class.java),
        task: RunningTaskInfo? = null,
    ): Pair<RunningTaskInfo, TaskListener> {
        val taskInfo = task ?: let {
            TestRunningTaskInfoBuilder().setTaskId(taskId).setDisplayId(displayId).build()
        }
        var listener: TaskListener? = null
        whenever(
            taskOrganizer.createRootTask(
                eq(displayId),
                anyOrNull(),
                any(TaskListener::class.java),
                eq(true)
            )
        ).thenAnswer {
            listener = it.arguments[2] as ShellTaskOrganizer.TaskListener
            listener!!.onTaskAppeared(taskInfo, leash)
        }
        controller.createRootTaskStack(displayId, rootTaskStackListener)
        return Pair(taskInfo, listener!!)
    }

    private fun setupChildTask(
        taskId: Int,
        parentTaskListener: TaskListener,
        leash: SurfaceControl = mock(SurfaceControl::class.java),
        task: RunningTaskInfo? = null,
    ): RunningTaskInfo {
        val taskInfo = task ?: let {
            TestRunningTaskInfoBuilder().setTaskId(taskId).setDisplayId(displayId).build()
        }
        parentTaskListener.onTaskAppeared(taskInfo, leash)
        return taskInfo
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        controller = AutoTaskStackControllerImpl(
            taskOrganizer,
            shellMainThread,
            transitions,
            shellInit,
            rootTdaOrganizer
        )
        mMainThreadHandler = Handler(Looper.getMainLooper())

        controller.autoTransitionHandlerDelegate = delegate
    }

    @Test
    fun createRootTask_rootTaskAppeared_callsOnTaskStackCreated() {
        // Arrange
        val taskInfo =
            TestRunningTaskInfoBuilder().setTaskId(32).setDisplayId(displayId).build()
        var listener: TaskListener? = null
        whenever(
            taskOrganizer.createRootTask(
                eq(displayId),
                anyOrNull(),
                any(TaskListener::class.java),
                eq(true)
            )
        ).thenAnswer {
            listener = it.arguments[2] as ShellTaskOrganizer.TaskListener
            listener!!.onTaskAppeared(taskInfo, mock(SurfaceControl::class.java))
        }

        // Act
        controller.createRootTaskStack(displayId, rootTaskStackListener)

        // Assert
        val captor = argumentCaptor<RootTaskStack>()
        verify(rootTaskStackListener).onRootTaskStackCreated(captor.capture())
        val taskStack = captor.firstValue
        assertThat(taskStack.id).isEqualTo(32)
    }

    @Test
    fun rootTaskInfoChanged_callsOnTaskStackInfoChanged() {
        // Arrange
        val (taskInfo, taskListener) = setupRootTask(taskId = 12)

        // Act
        val newTaskInfo = TestRunningTaskInfoBuilder().setDisplayId(displayId)
            .setTaskId(12)
            .setVisible(true)
            .build()
        taskListener.onTaskInfoChanged(newTaskInfo)

        // Assert
        val captor = argumentCaptor<RootTaskStack>()
        verify(rootTaskStackListener).onRootTaskStackInfoChanged(captor.capture())
        assertThat(captor.firstValue.rootTaskInfo.topActivity).isEqualTo(newTaskInfo.topActivity)
    }

    @Test
    fun rootTaskVanished_callsOnTaskStackDestroyed() {
        // Arrange
        val (taskInfo, taskListener) = setupRootTask(taskId = 12)

        // Act
        taskListener.onTaskVanished(taskInfo)

        // Assert
        verify(rootTaskStackListener).onRootTaskStackDestroyed(anyOrNull())
        assertThat(controller.taskStackStateMap[12]).isNull()
    }

    @Test
    fun destroyTaskStack_clearsStateAndCallsOnTaskStackDestroyed() {
        // Arrange
        val (taskInfo, taskListener) = setupRootTask(taskId = 12)
        whenever(
            taskOrganizer.deleteRootTask(any(WindowContainerToken::class.java))
        ).thenAnswer {
            taskListener.onTaskVanished(taskInfo)
            true
        }

        // Act
        controller.destroyTaskStack(taskStackId = 12)

        // Assert
        verify(rootTaskStackListener).onRootTaskStackDestroyed(anyOrNull())
        assertThat(controller.taskStackStateMap[taskInfo.taskId]).isNull()
    }

    @Test
    fun createRootTask_childTaskAppeared_callsOnTaskAppeared() {
        // Arrange
        val leash = mock(SurfaceControl::class.java)
        val (taskInfo, taskListener) = setupRootTask(taskId = 14)

        // Act
        val childTaskInfo = TestRunningTaskInfoBuilder().setParentTaskId(taskInfo.taskId)
            .setTaskId(101).build()
        taskListener.onTaskAppeared(childTaskInfo, leash)

        // Assert
        verify(rootTaskStackListener).onTaskAppeared(eq(childTaskInfo), eq(leash))
    }

    @Test
    fun createRootTask_childTaskInfoChanged_callsOnTaskInfoChanged() {
        // Arrange
        val (taskInfo, taskListener) = setupRootTask(taskId = 15)
        setupChildTask(taskId = 101, parentTaskListener = taskListener)

        // Act
        val newChildTaskInfo = TestRunningTaskInfoBuilder().setParentTaskId(taskInfo.taskId)
            .setTaskId(101)
            .setVisible(false)
            .build()
        taskListener.onTaskInfoChanged(newChildTaskInfo)

        // Assert
        verify(rootTaskStackListener).onTaskInfoChanged(eq(newChildTaskInfo))
    }

    @Test
    fun createRootTask_childTaskVanished_callsOnTaskVanished() {
        // Arrange
        val (taskInfo, taskListener) = setupRootTask(taskId = 23)
        val childTaskInfo = setupChildTask(taskId = 102, parentTaskListener = taskListener)

        // Act
        taskListener.onTaskVanished(childTaskInfo)

        // Assert
        verify(rootTaskStackListener).onTaskVanished(eq(childTaskInfo))
    }

    @Test
    fun setDefaultTaskStack_setsLaunchRoot() {
        // Arrange
        setupRootTask(taskId = 16)

        // Act
        controller.setDefaultRootTaskStackOnDisplay(displayId, rootTaskStackId = 16)

        // Assert
        val wctCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(taskOrganizer).applyTransaction(wctCaptor.capture())
        assertThat(wctCaptor.firstValue.isEmpty).isFalse()
        assertThat(wctCaptor.firstValue.hierarchyOps).hasSize(1)
        assertThat(wctCaptor.firstValue.hierarchyOps[0].type).isEqualTo(
            HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT
        )
    }

    @Test
    fun setDefaultTaskStack_null_clearsLaunchRoot() {
        // Arrange
        setupRootTask(taskId = 16)
        controller.setDefaultRootTaskStackOnDisplay(displayId, 16)

        // Act
        controller.setDefaultRootTaskStackOnDisplay(displayId, null)

        // Assert
        val wctCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(taskOrganizer, times(2)).applyTransaction(wctCaptor.capture())
        assertThat(wctCaptor.firstValue.isEmpty).isFalse()
        assertThat(wctCaptor.firstValue.hierarchyOps).hasSize(1)

        assertThat(wctCaptor.firstValue.hierarchyOps[0].type).isEqualTo(
            HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT
        )
        assertThat(wctCaptor.firstValue.hierarchyOps[0].windowingModes).isEqualTo(
            intArrayOf(WINDOWING_MODE_UNDEFINED)
        )
        assertThat(wctCaptor.firstValue.hierarchyOps[0].activityTypes).isEqualTo(
            intArrayOf(
                ACTIVITY_TYPE_STANDARD, ACTIVITY_TYPE_UNDEFINED, ACTIVITY_TYPE_RECENTS,
                ACTIVITY_TYPE_ASSISTANT
            )
        )

        assertThat(wctCaptor.secondValue.hierarchyOps).hasSize(1)
        assertThat(wctCaptor.secondValue.hierarchyOps[0].type).isEqualTo(
            HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT
        )
        assertThat(wctCaptor.secondValue.hierarchyOps[0].windowingModes).isNull()
        assertThat(wctCaptor.secondValue.hierarchyOps[0].activityTypes).isNull()
    }

    @Test
    fun setDefaultTaskStack_rootTaskInexistent_NoOp() {
        controller.setDefaultRootTaskStackOnDisplay(displayId, 1)

        verify(taskOrganizer, never()).applyTransaction(anyOrNull())
    }

    private fun setupTransitionReply(transitionId: IBinder) {
        whenever(
            transitions.startTransition(
                anyInt(),
                any(WindowContainerTransaction::class.java),
                any(Transitions.TransitionHandler::class.java)
            )
        ).thenAnswer {
            mMainThreadHandler!!.post({
                controller.startAnimation(transitionId,
                    TransitionInfo(1, 0),
                    mock(SurfaceControl.Transaction::class.java),
                    mock(SurfaceControl.Transaction::class.java),
                    {}
                )
            })
            transitionId
        }
    }

    @Test
    fun startTransition_withTaskStackStates_leadsToCorrectTranslationToWct() {
        // Arrange
        val (taskInfo, taskListener) = setupRootTask(taskId = 17)
        setupChildTask(taskId = 111, parentTaskListener = taskListener)

        val transitionId = Binder()
        setupTransitionReply(transitionId)

        // Act
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            taskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 10, 10), true, 0)
        )
        controller.startTransition(transaction)

        // Assert
        val wctCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(anyInt(), wctCaptor.capture(), anyOrNull())
        val wct = wctCaptor.firstValue
        val expected = WindowContainerTransaction()
            .setBounds(
                taskInfo.token, Rect(10, 10, 10, 10)
            )
            .reorder(taskInfo.token, true)
        assertThat(wct.toString()).isEqualTo(expected.toString())
    }

    @Test
    fun startTransition_withTaskStackStates_leadsToCorrectStartAnimation() {
        // Arrange
        val (rootTask, taskListener) = setupRootTask(taskId = 13)
        val startTransaction = mock(SurfaceControl.Transaction::class.java)
        val finishTransaction = mock(SurfaceControl.Transaction::class.java)
        val claim = Binder()
        whenever(
            transitions.startTransition(
                anyInt(),
                any(WindowContainerTransaction::class.java),
                any(Transitions.TransitionHandler::class.java)
            )
        ).thenAnswer {
            mMainThreadHandler!!.post({
                controller.startAnimation(
                    claim,
                    TransitionInfo(1, 0),
                    startTransaction,
                    finishTransaction,
                    mock(TransitionFinishCallback::class.java)
                )
            })
            claim
        }

        // Act
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            rootTask.taskId,
            AutoTaskStackState(Rect(10, 10, 10, 10), true, 3)
        )
        controller.startTransition(transaction)!!
        waitForMainThread()

        // Assert
        assertThat(delegate.lastStartTransaction).isEqualTo(startTransaction)
        assertThat(delegate.lastFinishTransaction).isEqualTo(finishTransaction)
        assertThat(delegate.lastTaskStackStates).isEqualTo(transaction.getTaskStackStates())
    }

    private fun waitForMainThread() {
        runOnMainThreadAndBlock({})
    }

    /**
     * Posts the given Runnable on the main thread, and blocks the calling thread until it's run.
     */
    private fun runOnMainThreadAndBlock(action: Runnable) {
        val latch = CountDownLatch(1)
        mMainThreadHandler!!.post {
            action.run()
            latch.countDown()
        }
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    @Test
    fun startTransition_withTaskStackStates_reordersRootTaskLeashes() {
        // Arrange
        val leash13 = mock(SurfaceControl::class.java)
        val leash15 = mock(SurfaceControl::class.java)
        val (rootTask13, taskListener13) = setupRootTask(taskId = 13, leash = leash13)
        val (rootTask15, taskListener15) = setupRootTask(taskId = 15, leash = leash15)

        val tdaLeash = mock(SurfaceControl::class.java)
        val startTransaction = mock(SurfaceControl.Transaction::class.java)
        val finishTransaction = mock(SurfaceControl.Transaction::class.java)
        val claim = Binder()
        whenever(
            transitions.startTransition(
                anyInt(),
                any(WindowContainerTransaction::class.java),
                any(Transitions.TransitionHandler::class.java)
            )
        ).thenAnswer {
            mMainThreadHandler!!.post({
                controller.startAnimation(
                    claim,
                    TransitionInfo(1, 0),
                    startTransaction,
                    finishTransaction,
                    mock(TransitionFinishCallback::class.java)
                )
            })
            claim
        }
        whenever(rootTdaOrganizer.getDisplayAreaLeash(anyInt())).thenReturn(tdaLeash)


        // Act
        val transaction = AutoTaskStackTransaction()
            .setTaskStackState(
                rootTask13.taskId,
                AutoTaskStackState(Rect(10, 10, 100, 100), true, 1)
            )
            .setTaskStackState(
                rootTask15.taskId,
                AutoTaskStackState(Rect(10, 20, 400, 400), true, 3)
            )
        controller.startTransition(transaction)!!
        waitForMainThread()

        // Assert
        verify(startTransaction).setLayer(leash13, 1)
        verify(startTransaction).setLayer(leash15, 3)

        verify(finishTransaction).setLayer(leash13, 1)
        verify(finishTransaction).setLayer(leash15, 3)
    }

    @Test
    fun transitionFromCore_delegateReturnsNull_handleRequestReturnsNull() {
        // Arrange
        val transition = mock(IBinder::class.java)
        val request = mock(TransitionRequestInfo::class.java)
        delegate.handleRequestReturn = null

        // Act
        val result = controller.handleRequest(transition, request)

        // Assert
        assertThat(result).isNull()
    }

    @Test
    fun transitionFromCore_delegateWithEmptyOperations_handleRequestReturnsNull() {
        // Arrange
        val transition = mock(IBinder::class.java)
        val request = mock(TransitionRequestInfo::class.java)
        delegate.handleRequestReturn = AutoTaskStackTransaction()

        // Act
        val result = controller.handleRequest(transition, request)

        // Assert
        assertThat(result).isNull()
    }

    @Test
    fun transitionFromCore_delegateWithTaskStackStates_handleRequestReturnsCorrect() {
        val leash = mock(SurfaceControl::class.java)
        val (taskInfo, listener) = setupRootTask(taskId = 18)

        val transaction = AutoTaskStackTransaction().setTaskStackState(
            taskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 30, 30), true, 0)
        )
        delegate.handleRequestReturn = transaction

        // Act
        val transition = mock(IBinder::class.java)
        val requestInfo = mock(TransitionRequestInfo::class.java)
        val result = controller.handleRequest(transition, requestInfo)

        // Assert
        assertThat(result).isNotNull()
        val expected = WindowContainerTransaction()
            .setBounds(
                taskInfo.token, Rect(10, 10, 30, 30)
            )
            .reorder(taskInfo.token, true)
        assertThat(result.toString()).isEqualTo(expected.toString())
    }

    @Test
    fun transitionFromCore_notPlayedByDelegate_withoutTaskStackChange_NotPlayed() {
        // Arrange
        val (taskInfo, listener) = setupRootTask(taskId = 18)
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            taskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 30, 30), true, 0)
        )
        delegate.handleRequestReturn = transaction
        delegate.play = false

        val transition = mock(IBinder::class.java)
        val requestInfo = mock(TransitionRequestInfo::class.java)
        controller.handleRequest(transition, requestInfo)

        // Act
        val result = controller.startAnimation(
            transition,
            TransitionInfo(1, 0),
            mock(SurfaceControl.Transaction::class.java),
            mock(SurfaceControl.Transaction::class.java),
            mock(TransitionFinishCallback::class.java)
        )

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun transitionFromCore_notPlayedByDelegate_containsTaskStackChange_shouldBePlayed() {
        // Arrange
        val taskLeash = mock(SurfaceControl::class.java)
        val (rootTaskInfo, listener) = setupRootTask(taskId = 18, leash = taskLeash)
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            rootTaskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 30, 30), true, 0)
        )
        delegate.handleRequestReturn = transaction
        delegate.play = false

        val transition = mock(IBinder::class.java)
        val requestInfo = mock(TransitionRequestInfo::class.java)
        controller.handleRequest(transition, requestInfo)
        val info = TransitionInfoBuilder(1)
            .addChange(TransitionInfo.Change(rootTaskInfo.token, taskLeash).apply {
                taskInfo = rootTaskInfo
            })
            .build()

        // Act
        val result = controller.startAnimation(
            transition,
            info,
            mock(SurfaceControl.Transaction::class.java),
            mock(SurfaceControl.Transaction::class.java),
            mock(TransitionFinishCallback::class.java)
        )

        // Assert
        assertThat(result).isTrue()
    }
}
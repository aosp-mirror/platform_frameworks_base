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

package com.android.wm.shell.automotive

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceControl
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TransitionFinishCallback

/**
 * Delegate interface for handling auto task stack transitions.
 */
interface AutoTaskStackTransitionHandlerDelegate {
    /**
     * Handles a transition request.
     *
     * @param transition The transition identifier.
     * @param request The transition request information.
     * @return An [AutoTaskStackTransaction] to be applied for the transition, or null if the
     *         animation is not handled by this delegate.
     */
    fun handleRequest(
        transition: IBinder, request: TransitionRequestInfo
    ): AutoTaskStackTransaction?

    /**
     * See [Transitions.TransitionHandler.startAnimation] for more details.
     *
     * @param changedTaskStacks Contains the states of the task stacks that were changed as a
     * result of this transition. The key is the [AutoTaskStack.id] and the value is the
     * corresponding [AutoTaskStackState].
     */
    fun startAnimation(
        transition: IBinder,
        changedTaskStacks: Map<Int, AutoTaskStackState>,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: TransitionFinishCallback
    ): Boolean

    /**
     * See [Transitions.TransitionHandler.onTransitionConsumed] for more details.
     *
     * @param requestedTaskStacks contains the states of the task stacks that were requested in
     * the transition. The key is the [AutoTaskStack.id] and the value is the corresponding
     * [AutoTaskStackState].
     */
    fun onTransitionConsumed(
        transition: IBinder,
        requestedTaskStacks: Map<Int, AutoTaskStackState>,
        aborted: Boolean, finishTransaction: SurfaceControl.Transaction?
    )

    /**
     * See [Transitions.TransitionHandler.mergeAnimation] for more details.
     *
     * @param changedTaskStacks Contains the states of the task stacks that were changed as a
     * result of this transition. The key is the [AutoTaskStack.id] and the value is the
     * corresponding [AutoTaskStackState].
     */
    fun mergeAnimation(
        transition: IBinder,
        changedTaskStacks: Map<Int, AutoTaskStackState>,
        info: TransitionInfo,
        surfaceTransaction: SurfaceControl.Transaction,
        mergeTarget: IBinder,
        finishCallback: TransitionFinishCallback
    )
}


/**
 * Controller for managing auto task stacks.
 */
interface AutoTaskStackController {

    var autoTransitionHandlerDelegate: AutoTaskStackTransitionHandlerDelegate?
        set

    /**
     * Map of task stack IDs to their states.
     *
     * This gets updated right before [AutoTaskStackTransitionHandlerDelegate.startAnimation] or
     * [AutoTaskStackTransitionHandlerDelegate.onTransitionConsumed] is called.
     */
    val taskStackStateMap: Map<Int, AutoTaskStackState>
        get

    /**
     * Creates a new multi-window root task.
     *
     * A root task stack is placed in the default TDA of the specified display by default.
     * Once the root task is removed, the [AutoTaskStackController] no longer holds a reference to
     * it.
     *
     * @param displayId The ID of the display to create the root task stack on.
     * @param listener The listener for root task stack events.
     */
    @ShellMainThread
    fun createRootTaskStack(displayId: Int, listener: RootTaskStackListener)


    /**
     * Sets the default root task stack (launch root) on a display. Calling it again with a
     * different [rootTaskStackId] will simply replace the default root task stack on the display.
     *
     * Note: This is helpful for passively routing tasks to a specified container. If a display
     * doesn't have a default root task stack set, all tasks will open in fullscreen and cover
     * the entire default TDA by default.
     *
     * @param displayId The ID of the display.
     * @param rootTaskStackId The ID of the root task stack, or null to clear the default.
     */
    @ShellMainThread
    fun setDefaultRootTaskStackOnDisplay(displayId: Int, rootTaskStackId: Int?)

    /**
     * Starts a transaction with the specified [transaction].
     * Returns the transition identifier.
     */
    @ShellMainThread
    fun startTransition(transaction: AutoTaskStackTransaction): IBinder?
}

internal sealed class TaskStackOperation {
    data class ReparentTask(
        val taskId: Int,
        val parentTaskStackId: Int,
        val onTop: Boolean
    ) : TaskStackOperation()

    data class SendPendingIntent(
        val sender: PendingIntent,
        val intent: Intent,
        val options: Bundle?
    ) : TaskStackOperation()

    data class SetTaskStackState(
        val taskStackId: Int,
        val state: AutoTaskStackState
    ) : TaskStackOperation()
}

data class AutoTaskStackTransaction internal constructor(
    internal val operations: MutableList<TaskStackOperation> = mutableListOf()
) {
    constructor() : this(
        mutableListOf()
    )

    /** See [WindowContainerTransaction.reparent] for more details. */
    fun reparentTask(
        taskId: Int,
        parentTaskStackId: Int,
        onTop: Boolean
    ): AutoTaskStackTransaction {
        operations.add(TaskStackOperation.ReparentTask(taskId, parentTaskStackId, onTop))
        return this
    }

    /** See [WindowContainerTransaction.sendPendingIntent] for more details. */
    fun sendPendingIntent(
        sender: PendingIntent,
        intent: Intent,
        options: Bundle?
    ): AutoTaskStackTransaction {
        operations.add(TaskStackOperation.SendPendingIntent(sender, intent, options))
        return this
    }

    /**
     * Adds a set task stack state operation to the transaction.
     *
     * If an operation with the same task stack ID already exists, it is replaced with the new one.
     *
     * @param taskStackId The ID of the task stack.
     * @param state The new state of the task stack.
     * @return The transaction with the added operation.
     */
    fun setTaskStackState(taskStackId: Int, state: AutoTaskStackState): AutoTaskStackTransaction {
        val existingOperation = operations.find {
            it is TaskStackOperation.SetTaskStackState && it.taskStackId == taskStackId
        }
        if (existingOperation != null) {
            val index = operations.indexOf(existingOperation)
            operations[index] = TaskStackOperation.SetTaskStackState(taskStackId, state)
        } else {
            operations.add(TaskStackOperation.SetTaskStackState(taskStackId, state))
        }
        return this
    }

    /**
     * Returns a map of task stack IDs to their states from the set task stack state operations.
     *
     * @return The map of task stack IDs to states.
     */
    fun getTaskStackStates(): Map<Int, AutoTaskStackState> {
        val states = mutableMapOf<Int, AutoTaskStackState>()
        operations.forEach { operation ->
            if (operation is TaskStackOperation.SetTaskStackState) {
                states[operation.taskStackId] = operation.state
            }
        }
        return states
    }
}


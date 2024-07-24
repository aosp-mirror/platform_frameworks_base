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

package com.android.systemui.dreams.homecontrols

import android.app.Activity
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.content.Intent
import android.graphics.Rect
import android.os.Binder
import android.window.TaskFragmentCreationParams
import android.window.TaskFragmentInfo
import android.window.TaskFragmentOperation
import android.window.TaskFragmentOperation.OP_TYPE_DELETE_TASK_FRAGMENT
import android.window.TaskFragmentOperation.OP_TYPE_REORDER_TO_TOP_OF_TASK
import android.window.TaskFragmentOrganizer
import android.window.TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_CHANGE
import android.window.TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_CLOSE
import android.window.TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_OPEN
import android.window.TaskFragmentTransaction
import android.window.TaskFragmentTransaction.TYPE_ACTIVITY_REPARENTED_TO_TASK
import android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_APPEARED
import android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_ERROR
import android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_INFO_CHANGED
import android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED
import android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_VANISHED
import android.window.WindowContainerTransaction
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.concurrency.DelayableExecutor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

typealias FragmentInfoCallback = (TaskFragmentInfo) -> Unit

/** Wrapper around TaskFragmentOrganizer for managing a task fragment within an activity */
class TaskFragmentComponent
@AssistedInject
constructor(
    @Assisted private val activity: Activity,
    @Assisted("onCreateCallback") private val onCreateCallback: FragmentInfoCallback,
    @Assisted("onInfoChangedCallback") private val onInfoChangedCallback: FragmentInfoCallback,
    @Assisted private val hide: () -> Unit,
    @Main private val executor: DelayableExecutor,
) {

    @AssistedFactory
    fun interface Factory {
        fun create(
            activity: Activity,
            @Assisted("onCreateCallback") onCreateCallback: FragmentInfoCallback,
            @Assisted("onInfoChangedCallback") onInfoChangedCallback: FragmentInfoCallback,
            hide: () -> Unit
        ): TaskFragmentComponent
    }

    private val fragmentToken = Binder()
    private val organizer: TaskFragmentOrganizer =
        object : TaskFragmentOrganizer(executor) {

                override fun onTransactionReady(transaction: TaskFragmentTransaction) {
                    handleTransactionReady(transaction)
                }
            }
            .apply { registerOrganizer(true /* isSystemOrganizer */) }

    private fun handleTransactionReady(transaction: TaskFragmentTransaction) {
        val resultT = WindowContainerTransaction()

        for (change in transaction.changes) {
            change.taskFragmentInfo?.let { taskFragmentInfo ->
                if (taskFragmentInfo.fragmentToken == fragmentToken) {
                    when (change.type) {
                        TYPE_TASK_FRAGMENT_APPEARED -> {
                            resultT.addTaskFragmentOperation(
                                fragmentToken,
                                TaskFragmentOperation.Builder(OP_TYPE_REORDER_TO_TOP_OF_TASK)
                                    .build()
                            )

                            onCreateCallback(taskFragmentInfo)
                        }
                        TYPE_TASK_FRAGMENT_INFO_CHANGED -> {
                            onInfoChangedCallback(taskFragmentInfo)
                        }
                        TYPE_TASK_FRAGMENT_VANISHED -> {
                            hide()
                        }
                        TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED -> {}
                        TYPE_TASK_FRAGMENT_ERROR -> {
                            hide()
                        }
                        TYPE_ACTIVITY_REPARENTED_TO_TASK -> {}
                        else ->
                            throw IllegalArgumentException(
                                "Unknown TaskFragmentEvent=" + change.type
                            )
                    }
                }
            }
        }
        organizer.onTransactionHandled(
            transaction.transactionToken,
            resultT,
            TASK_FRAGMENT_TRANSIT_CHANGE,
            false
        )
    }

    /** Creates the task fragment */
    fun createTaskFragment() {
        val fragmentOptions =
            TaskFragmentCreationParams.Builder(
                    organizer.organizerToken,
                    fragmentToken,
                    activity.activityToken!!
                )
                .setInitialRelativeBounds(Rect())
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN)
                .build()
        organizer.applyTransaction(
            WindowContainerTransaction().createTaskFragment(fragmentOptions),
            TASK_FRAGMENT_TRANSIT_CHANGE,
            false
        )
    }

    private fun WindowContainerTransaction.startActivity(intent: Intent) =
        this.startActivityInTaskFragment(fragmentToken, activity.activityToken!!, intent, null)

    /** Starts the provided activity in the fragment and move it to the background */
    fun startActivityInTaskFragment(intent: Intent) {
        organizer.applyTransaction(
            WindowContainerTransaction().startActivity(intent),
            TASK_FRAGMENT_TRANSIT_OPEN,
            false
        )
    }

    /** Destroys the task fragment */
    fun destroy() {
        organizer.applyTransaction(
            WindowContainerTransaction()
                .addTaskFragmentOperation(
                    fragmentToken,
                    TaskFragmentOperation.Builder(OP_TYPE_DELETE_TASK_FRAGMENT).build()
                ),
            TASK_FRAGMENT_TRANSIT_CLOSE,
            false
        )
    }
}

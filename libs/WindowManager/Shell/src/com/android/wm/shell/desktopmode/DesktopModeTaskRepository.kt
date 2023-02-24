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

import android.util.ArraySet

/**
 * Keeps track of task data related to desktop mode.
 */
class DesktopModeTaskRepository {

    /**
     * Set of task ids that are marked as active in desktop mode.
     * Active tasks in desktop mode are freeform tasks that are visible or have been visible after
     * desktop mode was activated.
     * Task gets removed from this list when it vanishes. Or when desktop mode is turned off.
     */
    private val activeTasks = ArraySet<Int>()
    private val listeners = ArraySet<Listener>()

    /**
     * Add a [Listener] to be notified of updates to the repository.
     */
    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    /**
     * Remove a previously registered [Listener]
     */
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Mark a task with given [taskId] as active.
     */
    fun addActiveTask(taskId: Int) {
        val added = activeTasks.add(taskId)
        if (added) {
            listeners.onEach { it.onActiveTasksChanged() }
        }
    }

    /**
     * Remove task with given [taskId] from active tasks.
     */
    fun removeActiveTask(taskId: Int) {
        val removed = activeTasks.remove(taskId)
        if (removed) {
            listeners.onEach { it.onActiveTasksChanged() }
        }
    }

    /**
     * Check if a task with the given [taskId] was marked as an active task
     */
    fun isActiveTask(taskId: Int): Boolean {
        return activeTasks.contains(taskId)
    }

    /**
     * Get a set of the active tasks
     */
    fun getActiveTasks(): ArraySet<Int> {
        return ArraySet(activeTasks)
    }

    /**
     * Defines interface for classes that can listen to changes in repository state.
     */
    interface Listener {
        fun onActiveTasksChanged()
    }
}

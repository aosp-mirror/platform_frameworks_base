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

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestShellExecutor
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopModeTaskRepositoryTest : ShellTestCase() {

    private lateinit var repo: DesktopModeTaskRepository

    @Before
    fun setUp() {
        repo = DesktopModeTaskRepository()
    }

    @Test
    fun addActiveTask_listenerNotifiedAndTaskIsActive() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)

        repo.addActiveTask(1)
        assertThat(listener.activeTaskChangedCalls).isEqualTo(1)
        assertThat(repo.isActiveTask(1)).isTrue()
    }

    @Test
    fun addActiveTask_sameTaskDoesNotNotify() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)

        repo.addActiveTask(1)
        repo.addActiveTask(1)
        assertThat(listener.activeTaskChangedCalls).isEqualTo(1)
    }

    @Test
    fun addActiveTask_multipleTasksAddedNotifiesForEach() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)

        repo.addActiveTask(1)
        repo.addActiveTask(2)
        assertThat(listener.activeTaskChangedCalls).isEqualTo(2)
    }

    @Test
    fun removeActiveTask_listenerNotifiedAndTaskNotActive() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)

        repo.addActiveTask(1)
        repo.removeActiveTask(1)
        // Notify once for add and once for remove
        assertThat(listener.activeTaskChangedCalls).isEqualTo(2)
        assertThat(repo.isActiveTask(1)).isFalse()
    }

    @Test
    fun removeActiveTask_removeNotExistingTaskDoesNotNotify() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)
        repo.removeActiveTask(99)
        assertThat(listener.activeTaskChangedCalls).isEqualTo(0)
    }

    @Test
    fun isActiveTask_notExistingTaskReturnsFalse() {
        assertThat(repo.isActiveTask(99)).isFalse()
    }

    @Test
    fun addListener_notifiesVisibleFreeformTask() {
        repo.updateVisibleFreeformTasks(1, true)
        val listener = TestVisibilityListener()
        val executor = TestShellExecutor()
        repo.addVisibleTasksListener(listener, executor)
        executor.flushAll()

        assertThat(listener.hasVisibleFreeformTasks).isTrue()
        assertThat(listener.visibleFreeformTaskChangedCalls).isEqualTo(1)
    }

    @Test
    fun updateVisibleFreeformTasks_addVisibleTasksNotifiesListener() {
        val listener = TestVisibilityListener()
        val executor = TestShellExecutor()
        repo.addVisibleTasksListener(listener, executor)
        repo.updateVisibleFreeformTasks(1, true)
        repo.updateVisibleFreeformTasks(2, true)
        executor.flushAll()

        assertThat(listener.hasVisibleFreeformTasks).isTrue()
        // Equal to 2 because adding the listener notifies the current state
        assertThat(listener.visibleFreeformTaskChangedCalls).isEqualTo(2)
    }

    @Test
    fun updateVisibleFreeformTasks_removeVisibleTasksNotifiesListener() {
        val listener = TestVisibilityListener()
        val executor = TestShellExecutor()
        repo.addVisibleTasksListener(listener, executor)
        repo.updateVisibleFreeformTasks(1, true)
        repo.updateVisibleFreeformTasks(2, true)
        executor.flushAll()

        assertThat(listener.hasVisibleFreeformTasks).isTrue()
        repo.updateVisibleFreeformTasks(1, false)
        executor.flushAll()

        // Equal to 2 because adding the listener notifies the current state
        assertThat(listener.visibleFreeformTaskChangedCalls).isEqualTo(2)

        repo.updateVisibleFreeformTasks(2, false)
        executor.flushAll()

        assertThat(listener.hasVisibleFreeformTasks).isFalse()
        assertThat(listener.visibleFreeformTaskChangedCalls).isEqualTo(3)
    }

    @Test
    fun addOrMoveFreeformTaskToTop_didNotExist_addsToTop() {
        repo.addOrMoveFreeformTaskToTop(5)
        repo.addOrMoveFreeformTaskToTop(6)
        repo.addOrMoveFreeformTaskToTop(7)

        val tasks = repo.getFreeformTasksInZOrder()
        assertThat(tasks.size).isEqualTo(3)
        assertThat(tasks[0]).isEqualTo(7)
        assertThat(tasks[1]).isEqualTo(6)
        assertThat(tasks[2]).isEqualTo(5)
    }

    @Test
    fun addOrMoveFreeformTaskToTop_alreadyExists_movesToTop() {
        repo.addOrMoveFreeformTaskToTop(5)
        repo.addOrMoveFreeformTaskToTop(6)
        repo.addOrMoveFreeformTaskToTop(7)

        repo.addOrMoveFreeformTaskToTop(6)

        val tasks = repo.getFreeformTasksInZOrder()
        assertThat(tasks.size).isEqualTo(3)
        assertThat(tasks.first()).isEqualTo(6)
    }

    class TestListener : DesktopModeTaskRepository.ActiveTasksListener {
        var activeTaskChangedCalls = 0
        override fun onActiveTasksChanged() {
            activeTaskChangedCalls++
        }
    }

    class TestVisibilityListener : DesktopModeTaskRepository.VisibleTasksListener {
        var hasVisibleFreeformTasks = false
        var visibleFreeformTaskChangedCalls = 0

        override fun onVisibilityChanged(hasVisibleTasks: Boolean) {
            hasVisibleFreeformTasks = hasVisibleTasks
            visibleFreeformTaskChangedCalls++
        }
    }
}

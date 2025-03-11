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

import android.graphics.Rect
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import android.util.ArraySet
import android.view.Display.DEFAULT_DISPLAY
import android.view.Display.INVALID_DISPLAY
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.desktopmode.persistence.Desktop
import com.android.wm.shell.desktopmode.persistence.DesktopPersistentRepository
import com.android.wm.shell.sysui.ShellInit
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.spy
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@ExperimentalCoroutinesApi
class DesktopRepositoryTest : ShellTestCase() {

    @JvmField @Rule val setFlagsRule = SetFlagsRule()

    private lateinit var repo: DesktopRepository
    private lateinit var shellInit: ShellInit
    private lateinit var datastoreScope: CoroutineScope

    @Mock private lateinit var testExecutor: ShellExecutor
    @Mock private lateinit var persistentRepository: DesktopPersistentRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        datastoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        shellInit = spy(ShellInit(testExecutor))

        repo =
            DesktopRepository(
                persistentRepository,
                datastoreScope,
                DEFAULT_USER_ID
            )
        whenever(runBlocking { persistentRepository.readDesktop(any(), any()) }).thenReturn(
            Desktop.getDefaultInstance()
        )
        shellInit.init()
    }

    @After
    fun tearDown() {
        datastoreScope.cancel()
    }

    @Test
    fun addTask_notifiesActiveTaskListener() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)

        repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        assertThat(listener.activeChangesOnDefaultDisplay).isEqualTo(1)
    }

    @Test
    fun addTask_marksTaskActive() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)

        repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        assertThat(repo.isActiveTask(1)).isTrue()
    }

    @Test
    fun addSameTaskTwice_notifiesOnce() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)

        repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        assertThat(listener.activeChangesOnDefaultDisplay).isEqualTo(1)
    }

    @Test
    fun addTask_multipleTasksAdded_notifiesForAllTasks() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)

        repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        repo.addTask(DEFAULT_DISPLAY, taskId = 2, isVisible = true)

        assertThat(listener.activeChangesOnDefaultDisplay).isEqualTo(2)
    }

    @Test
    fun addTask_multipleDisplays_notifiesCorrectListener() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)

        repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        repo.addTask(DEFAULT_DISPLAY, taskId = 2, isVisible = true)
        repo.addTask(SECOND_DISPLAY, taskId = 3, isVisible = true)

        assertThat(listener.activeChangesOnDefaultDisplay).isEqualTo(2)
        assertThat(listener.activeChangesOnSecondaryDisplay).isEqualTo(1)
    }

    @Test
    fun addTask_multipleDisplays_moveToAnotherDisplay() {
        repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        repo.addTask(SECOND_DISPLAY, taskId = 1, isVisible = true)
        assertThat(repo.getFreeformTasksInZOrder(DEFAULT_DISPLAY)).isEmpty()
        assertThat(repo.getFreeformTasksInZOrder(SECOND_DISPLAY)).containsExactly(1)
    }

    @Test
    fun removeActiveTask_notifiesActiveTaskListener() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)
        repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        repo.removeActiveTask(1)

        // Notify once for add and once for remove
        assertThat(listener.activeChangesOnDefaultDisplay).isEqualTo(2)
    }

    @Test
    fun removeActiveTask_marksTaskNotActive() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)
        repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        repo.removeActiveTask(1)

        assertThat(repo.isActiveTask(1)).isFalse()
    }

    @Test
    fun removeActiveTask_nonExistingTask_doesNotNotify() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)

        repo.removeActiveTask(99)

        assertThat(listener.activeChangesOnDefaultDisplay).isEqualTo(0)
    }

    @Test
    fun remoteActiveTask_listenerForOtherDisplayNotNotified() {
        val listener = TestListener()
        repo.addActiveTaskListener(listener)
        repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        repo.removeActiveTask(1)

        assertThat(listener.activeChangesOnSecondaryDisplay).isEqualTo(0)
        assertThat(repo.isActiveTask(1)).isFalse()
    }

    @Test
    fun isActiveTask_nonExistingTask_returnsFalse() {
        assertThat(repo.isActiveTask(99)).isFalse()
    }

    @Test
    fun isOnlyVisibleNonClosingTask_noTasks_returnsFalse() {
        // No visible tasks
        assertThat(repo.isOnlyVisibleNonClosingTask(1)).isFalse()
    }

    @Test
    fun isClosingTask_noTasks_returnsFalse() {
        // No visible tasks
        assertThat(repo.isClosingTask(1)).isFalse()
    }

    @Test
    fun updateTask_singleVisibleNonClosingTask_updatesTasksCorrectly() {
        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        assertThat(repo.isVisibleTask(1)).isTrue()
        assertThat(repo.isClosingTask(1)).isFalse()
        assertThat(repo.isOnlyVisibleNonClosingTask(1)).isTrue()

        assertThat(repo.isVisibleTask(99)).isFalse()
        assertThat(repo.isClosingTask(99)).isFalse()
        assertThat(repo.isOnlyVisibleNonClosingTask(99)).isFalse()
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE)
    fun updateTaskVisibility_multipleTasks_persistsVisibleTasks() =
        runTest(StandardTestDispatcher()) {
            repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
            repo.updateTask(DEFAULT_DISPLAY, taskId = 2, isVisible = true)

            inOrder(persistentRepository).run {
                verify(persistentRepository)
                    .addOrUpdateDesktop(
                        DEFAULT_USER_ID,
                        DEFAULT_DESKTOP_ID,
                        visibleTasks = ArraySet(arrayOf(1)),
                        minimizedTasks = ArraySet(),
                        freeformTasksInZOrder = arrayListOf()
                    )
                verify(persistentRepository)
                    .addOrUpdateDesktop(
                        DEFAULT_USER_ID,
                        DEFAULT_DESKTOP_ID,
                        visibleTasks = ArraySet(arrayOf(1, 2)),
                        minimizedTasks = ArraySet(),
                        freeformTasksInZOrder = arrayListOf()
                    )
            }
        }

    @Test
    fun isOnlyVisibleNonClosingTask_singleVisibleClosingTask() {
        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        repo.addClosingTask(DEFAULT_DISPLAY, 1)

        // A visible task that's closing
        assertThat(repo.isVisibleTask(1)).isTrue()
        assertThat(repo.isClosingTask(1)).isTrue()
        assertThat(repo.isOnlyVisibleNonClosingTask(1)).isFalse()
        // Not a visible task
        assertThat(repo.isVisibleTask(99)).isFalse()
        assertThat(repo.isOnlyVisibleNonClosingTask(99)).isFalse()
    }

    @Test
    fun isOnlyVisibleNonClosingTask_singleVisibleMinimizedTask() {
        val taskId = 1
        repo.addTask(DEFAULT_DISPLAY, taskId, isVisible = true)
        repo.minimizeTask(DEFAULT_DISPLAY, taskId)

        // The visible task that's closing
        assertThat(repo.isVisibleTask(taskId)).isFalse()
        assertThat(repo.isMinimizedTask(taskId)).isTrue()
        assertThat(repo.isOnlyVisibleNonClosingTask(taskId)).isFalse()
        // Not a visible task
        assertThat(repo.isVisibleTask(99)).isFalse()
        assertThat(repo.isOnlyVisibleNonClosingTask(99)).isFalse()
    }

    @Test
    fun isOnlyVisibleNonClosingTask_multipleVisibleNonClosingTasks() {
        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        repo.updateTask(DEFAULT_DISPLAY, taskId = 2, isVisible = true)

        // Not the only task
        assertThat(repo.isVisibleTask(1)).isTrue()
        assertThat(repo.isClosingTask(1)).isFalse()
        assertThat(repo.isOnlyVisibleNonClosingTask(1)).isFalse()

        // Not the only task
        assertThat(repo.isVisibleTask(2)).isTrue()
        assertThat(repo.isClosingTask(2)).isFalse()
        assertThat(repo.isOnlyVisibleNonClosingTask(2)).isFalse()

        // Not a visible task
        assertThat(repo.isVisibleTask(99)).isFalse()
        assertThat(repo.isClosingTask(99)).isFalse()
        assertThat(repo.isOnlyVisibleNonClosingTask(99)).isFalse()
    }

    @Test
    fun isOnlyVisibleNonClosingTask_multipleDisplays() {
        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        repo.updateTask(DEFAULT_DISPLAY, taskId = 2, isVisible = true)
        repo.updateTask(SECOND_DISPLAY, taskId = 3, isVisible = true)

        // Not the only task on DEFAULT_DISPLAY
        assertThat(repo.isVisibleTask(1)).isTrue()
        assertThat(repo.isOnlyVisibleNonClosingTask(1)).isFalse()
        // Not the only task on DEFAULT_DISPLAY
        assertThat(repo.isVisibleTask(2)).isTrue()
        assertThat(repo.isOnlyVisibleNonClosingTask(2)).isFalse()
        // The only visible task on SECOND_DISPLAY
        assertThat(repo.isVisibleTask(3)).isTrue()
        assertThat(repo.isOnlyVisibleNonClosingTask(3)).isTrue()
        // Not a visible task
        assertThat(repo.isVisibleTask(99)).isFalse()
        assertThat(repo.isOnlyVisibleNonClosingTask(99)).isFalse()
    }

    @Test
    fun addVisibleTasksListener_notifiesVisibleFreeformTask() {
        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        val listener = TestVisibilityListener()
        val executor = TestShellExecutor()

        repo.addVisibleTasksListener(listener, executor)
        executor.flushAll()

        assertThat(listener.visibleTasksCountOnDefaultDisplay).isEqualTo(1)
        assertThat(listener.visibleChangesOnDefaultDisplay).isEqualTo(1)
    }

    @Test
    fun addListener_tasksOnDifferentDisplay_doesNotNotify() {
        repo.updateTask(SECOND_DISPLAY, taskId = 1, isVisible = true)
        val listener = TestVisibilityListener()
        val executor = TestShellExecutor()
        repo.addVisibleTasksListener(listener, executor)
        executor.flushAll()

        assertThat(listener.visibleTasksCountOnDefaultDisplay).isEqualTo(0)
        // One call as adding listener notifies it
        assertThat(listener.visibleChangesOnDefaultDisplay).isEqualTo(0)
    }

    @Test
    fun updateTask_visible_addVisibleTasksNotifiesListener() {
        val listener = TestVisibilityListener()
        val executor = TestShellExecutor()
        repo.addVisibleTasksListener(listener, executor)

        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        repo.updateTask(DEFAULT_DISPLAY, taskId = 2, isVisible = true)
        executor.flushAll()

        assertThat(listener.visibleTasksCountOnDefaultDisplay).isEqualTo(2)
        assertThat(listener.visibleChangesOnDefaultDisplay).isEqualTo(2)
    }

    @Test
    fun updateTask_visibleTask_addVisibleTaskNotifiesListenerForThatDisplay() {
        val listener = TestVisibilityListener()
        val executor = TestShellExecutor()
        repo.addVisibleTasksListener(listener, executor)

        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        executor.flushAll()

        assertThat(listener.visibleTasksCountOnDefaultDisplay).isEqualTo(1)
        assertThat(listener.visibleChangesOnDefaultDisplay).isEqualTo(1)
        assertThat(listener.visibleTasksCountOnSecondaryDisplay).isEqualTo(0)
        assertThat(listener.visibleChangesOnSecondaryDisplay).isEqualTo(0)

        repo.updateTask(displayId = 1, taskId = 2, isVisible = true)
        executor.flushAll()

        // Listener for secondary display is notified
        assertThat(listener.visibleTasksCountOnSecondaryDisplay).isEqualTo(1)
        assertThat(listener.visibleChangesOnSecondaryDisplay).isEqualTo(1)
        // No changes to listener for default display
        assertThat(listener.visibleChangesOnDefaultDisplay).isEqualTo(1)
    }

    @Test
    fun updateTask_taskOnDefaultBecomesVisibleOnSecondDisplay_listenersNotified() {
        val listener = TestVisibilityListener()
        val executor = TestShellExecutor()
        repo.addVisibleTasksListener(listener, executor)

        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        executor.flushAll()
        assertThat(listener.visibleTasksCountOnDefaultDisplay).isEqualTo(1)

        // Mark task 1 visible on secondary display
        repo.updateTask(displayId = 1, taskId = 1, isVisible = true)
        executor.flushAll()

        // Default display should have 2 calls
        // 1 - visible task added
        // 2 - visible task removed
        assertThat(listener.visibleChangesOnDefaultDisplay).isEqualTo(2)
        assertThat(listener.visibleTasksCountOnDefaultDisplay).isEqualTo(0)

        // Secondary display should have 1 call for visible task added
        assertThat(listener.visibleChangesOnSecondaryDisplay).isEqualTo(1)
        assertThat(listener.visibleTasksCountOnSecondaryDisplay).isEqualTo(1)
    }

    @Test
    fun updateTask_removeVisibleTasksNotifiesListener() {
        val listener = TestVisibilityListener()
        val executor = TestShellExecutor()
        repo.addVisibleTasksListener(listener, executor)
        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        repo.updateTask(DEFAULT_DISPLAY, taskId = 2, isVisible = true)
        executor.flushAll()

        assertThat(listener.visibleTasksCountOnDefaultDisplay).isEqualTo(2)

        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = false)
        executor.flushAll()

        assertThat(listener.visibleChangesOnDefaultDisplay).isEqualTo(3)

        repo.updateTask(DEFAULT_DISPLAY, taskId = 2, isVisible = false)
        executor.flushAll()

        assertThat(listener.visibleTasksCountOnDefaultDisplay).isEqualTo(0)
        assertThat(listener.visibleChangesOnDefaultDisplay).isEqualTo(4)
    }

    /**
     * When a task vanishes, the displayId of the task is set to INVALID_DISPLAY.
     * This tests that task is removed from the last parent display when it vanishes.
     */
    @Test
    fun updateTask_removeVisibleTasksRemovesTaskWithInvalidDisplay() {
        val listener = TestVisibilityListener()
        val executor = TestShellExecutor()
        repo.addVisibleTasksListener(listener, executor)
        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        repo.updateTask(DEFAULT_DISPLAY, taskId = 2, isVisible = true)
        executor.flushAll()

        assertThat(listener.visibleTasksCountOnDefaultDisplay).isEqualTo(2)

        repo.updateTask(INVALID_DISPLAY, taskId = 1, isVisible = false)
        executor.flushAll()

        assertThat(listener.visibleChangesOnDefaultDisplay).isEqualTo(3)
        assertThat(listener.visibleTasksCountOnDefaultDisplay).isEqualTo(1)
    }

    @Test
    fun getVisibleTaskCount_defaultDisplay_returnsCorrectCount() {
        // No tasks, count is 0
        assertThat(repo.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(0)

        // New task increments count to 1
        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        assertThat(repo.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(1)

        // Visibility update to same task does not increase count
        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        assertThat(repo.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(1)

        // Second task visible increments count
        repo.updateTask(DEFAULT_DISPLAY, taskId = 2, isVisible = true)

        assertThat(repo.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(2)

        // Hiding a task decrements count
        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = false)
        assertThat(repo.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(1)

        // Hiding all tasks leaves count at 0
        repo.updateTask(DEFAULT_DISPLAY, taskId = 2, isVisible = false)
        assertThat(repo.getVisibleTaskCount(displayId = 9)).isEqualTo(0)

        // Hiding a not existing task, count remains at 0
        repo.updateTask(DEFAULT_DISPLAY, taskId = 999, isVisible = false)
        assertThat(repo.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(0)
    }

    @Test
    fun getVisibleTaskCount_multipleDisplays_returnsCorrectCount() {
        assertThat(repo.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(0)
        assertThat(repo.getVisibleTaskCount(SECOND_DISPLAY)).isEqualTo(0)

        // New task on default display increments count for that display only
        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        assertThat(repo.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(1)
        assertThat(repo.getVisibleTaskCount(SECOND_DISPLAY)).isEqualTo(0)

        // New task on secondary display, increments count for that display only
        repo.updateTask(SECOND_DISPLAY, taskId = 2, isVisible = true)

        assertThat(repo.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(1)
        assertThat(repo.getVisibleTaskCount(SECOND_DISPLAY)).isEqualTo(1)

        // Marking task visible on another display, updates counts for both displays
        repo.updateTask(SECOND_DISPLAY, taskId = 1, isVisible = true)

        assertThat(repo.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(0)
        assertThat(repo.getVisibleTaskCount(SECOND_DISPLAY)).isEqualTo(2)

        // Marking task that is on secondary display, hidden on default display, does not affect
        // secondary display
        repo.updateTask(DEFAULT_DISPLAY, taskId = 1, isVisible = false)

        assertThat(repo.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(0)
        assertThat(repo.getVisibleTaskCount(SECOND_DISPLAY)).isEqualTo(2)

        // Hiding a task on that display, decrements count
        repo.updateTask(SECOND_DISPLAY, taskId = 1, isVisible = false)

        assertThat(repo.getVisibleTaskCount(DEFAULT_DISPLAY)).isEqualTo(0)
        assertThat(repo.getVisibleTaskCount(SECOND_DISPLAY)).isEqualTo(1)
    }

    @Test
    fun addTask_didNotExist_addsToTop() {
        repo.addTask(DEFAULT_DISPLAY, 5, isVisible = true)
        repo.addTask(DEFAULT_DISPLAY, 6, isVisible = true)
        repo.addTask(DEFAULT_DISPLAY, 7, isVisible = true)

        val tasks = repo.getFreeformTasksInZOrder(DEFAULT_DISPLAY)
        assertThat(tasks.size).isEqualTo(3)
        assertThat(tasks[0]).isEqualTo(7)
        assertThat(tasks[1]).isEqualTo(6)
        assertThat(tasks[2]).isEqualTo(5)
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE)
    fun addTask_noTaskExists_persistenceEnabled_addsToTop() =
        runTest(StandardTestDispatcher()) {
            repo.addTask(DEFAULT_DISPLAY, 5, isVisible = true)
            repo.addTask(DEFAULT_DISPLAY, 6, isVisible = true)
            repo.addTask(DEFAULT_DISPLAY, 7, isVisible = true)

            val tasks = repo.getFreeformTasksInZOrder(DEFAULT_DISPLAY)
            assertThat(tasks).containsExactly(7, 6, 5).inOrder()
            inOrder(persistentRepository).run {
                verify(persistentRepository)
                    .addOrUpdateDesktop(
                        DEFAULT_USER_ID,
                        DEFAULT_DESKTOP_ID,
                        visibleTasks = ArraySet(),
                        minimizedTasks = ArraySet(),
                        freeformTasksInZOrder = arrayListOf(5)
                    )
                verify(persistentRepository)
                    .addOrUpdateDesktop(
                        DEFAULT_USER_ID,
                        DEFAULT_DESKTOP_ID,
                        visibleTasks = ArraySet(arrayOf(5)),
                        minimizedTasks = ArraySet(),
                        freeformTasksInZOrder = arrayListOf(6, 5)
                    )
                verify(persistentRepository)
                    .addOrUpdateDesktop(
                        DEFAULT_USER_ID,
                        DEFAULT_DESKTOP_ID,
                        visibleTasks = ArraySet(arrayOf(5, 6)),
                        minimizedTasks = ArraySet(),
                        freeformTasksInZOrder = arrayListOf(7, 6, 5)
                    )
            }
    }

    @Test
    fun addTask_alreadyExists_movesToTop() {
        repo.addTask(DEFAULT_DISPLAY, 5, isVisible = true)
        repo.addTask(DEFAULT_DISPLAY, 6, isVisible = true)
        repo.addTask(DEFAULT_DISPLAY, 7, isVisible = true)

        repo.addTask(DEFAULT_DISPLAY, 6, isVisible = true)

        val tasks = repo.getFreeformTasksInZOrder(DEFAULT_DISPLAY)
        assertThat(tasks.size).isEqualTo(3)
        assertThat(tasks.first()).isEqualTo(6)
    }

    @Test
    fun addTask_taskIsMinimized_unminimizesTask() {
        repo.addTask(DEFAULT_DISPLAY, 5, isVisible = true)
        repo.addTask(DEFAULT_DISPLAY, 6, isVisible = true)
        repo.addTask(DEFAULT_DISPLAY, 7, isVisible = true)
        repo.minimizeTask(displayId = 0, taskId = 6)

        val tasks = repo.getFreeformTasksInZOrder(DEFAULT_DISPLAY)
        assertThat(tasks).containsExactly(7, 6, 5).inOrder()
        assertThat(repo.isMinimizedTask(taskId = 6)).isTrue()
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE)
    fun minimizeTask_persistenceEnabled_taskIsPersistedAsMinimized() =
        runTest(StandardTestDispatcher()) {
            repo.addTask(DEFAULT_DISPLAY, 5, isVisible = true)
            repo.addTask(DEFAULT_DISPLAY, 6, isVisible = true)
            repo.addTask(DEFAULT_DISPLAY, 7, isVisible = true)

            repo.minimizeTask(displayId = 0, taskId = 6)

            val tasks = repo.getFreeformTasksInZOrder(DEFAULT_DISPLAY)
            assertThat(tasks).containsExactly(7, 6, 5).inOrder()
            assertThat(repo.isMinimizedTask(taskId = 6)).isTrue()
            inOrder(persistentRepository).run {
                verify(persistentRepository)
                    .addOrUpdateDesktop(
                        DEFAULT_USER_ID,
                        DEFAULT_DESKTOP_ID,
                        visibleTasks = ArraySet(),
                        minimizedTasks = ArraySet(),
                        freeformTasksInZOrder = arrayListOf(5)
                    )
                verify(persistentRepository)
                    .addOrUpdateDesktop(
                        DEFAULT_USER_ID,
                        DEFAULT_DESKTOP_ID,
                        visibleTasks = ArraySet(arrayOf(5)),
                        minimizedTasks = ArraySet(),
                        freeformTasksInZOrder = arrayListOf(6, 5)
                    )
                verify(persistentRepository)
                    .addOrUpdateDesktop(
                        DEFAULT_USER_ID,
                        DEFAULT_DESKTOP_ID,
                        visibleTasks = ArraySet(arrayOf(5, 6)),
                        minimizedTasks = ArraySet(),
                        freeformTasksInZOrder = arrayListOf(7, 6, 5)
                    )
                verify(persistentRepository, times(2))
                    .addOrUpdateDesktop(
                        DEFAULT_USER_ID,
                        DEFAULT_DESKTOP_ID,
                        visibleTasks = ArraySet(arrayOf(5, 7)),
                        minimizedTasks = ArraySet(arrayOf(6)),
                        freeformTasksInZOrder = arrayListOf(7, 6, 5)
                    )
            }
    }

    @Test
    fun addTask_taskIsUnminimized_noop() {
        repo.addTask(DEFAULT_DISPLAY, 5, isVisible = true)
        repo.addTask(DEFAULT_DISPLAY, 6, isVisible = true)
        repo.addTask(DEFAULT_DISPLAY, 7, isVisible = true)

        val tasks = repo.getFreeformTasksInZOrder(DEFAULT_DISPLAY)
        assertThat(tasks).containsExactly(7, 6, 5).inOrder()
        assertThat(repo.isMinimizedTask(taskId = 6)).isFalse()
    }

    @Test
    fun removeFreeformTask_invalidDisplay_removesTaskFromFreeformTasks() {
        repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        repo.removeFreeformTask(INVALID_DISPLAY, taskId = 1)

        val invalidDisplayTasks = repo.getFreeformTasksInZOrder(INVALID_DISPLAY)
        assertThat(invalidDisplayTasks).isEmpty()
        val validDisplayTasks = repo.getFreeformTasksInZOrder(DEFAULT_DISPLAY)
        assertThat(validDisplayTasks).isEmpty()
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE)
    fun removeFreeformTask_invalidDisplay_persistenceEnabled_removesTaskFromFreeformTasks() {
        runTest(StandardTestDispatcher()) {
            repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

            repo.removeFreeformTask(INVALID_DISPLAY, taskId = 1)

            verify(persistentRepository)
                .addOrUpdateDesktop(
                    DEFAULT_USER_ID,
                    DEFAULT_DESKTOP_ID,
                    visibleTasks = ArraySet(),
                    minimizedTasks = ArraySet(),
                    freeformTasksInZOrder = arrayListOf(1)
                )
            verify(persistentRepository)
                .addOrUpdateDesktop(
                    DEFAULT_USER_ID,
                    DEFAULT_DESKTOP_ID,
                    visibleTasks = ArraySet(),
                    minimizedTasks = ArraySet(),
                    freeformTasksInZOrder = ArrayList()
                )
        }
    }

    @Test
    fun removeFreeformTask_validDisplay_removesTaskFromFreeformTasks() {
        repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        repo.removeFreeformTask(DEFAULT_DISPLAY, taskId = 1)

        val tasks = repo.getFreeformTasksInZOrder(DEFAULT_DISPLAY)
        assertThat(tasks).isEmpty()
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE)
    fun removeFreeformTask_validDisplay_persistenceEnabled_removesTaskFromFreeformTasks() {
        runTest(StandardTestDispatcher()) {
            repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

            repo.removeFreeformTask(DEFAULT_DISPLAY, taskId = 1)

            verify(persistentRepository)
                .addOrUpdateDesktop(
                    DEFAULT_USER_ID,
                    DEFAULT_DESKTOP_ID,
                    visibleTasks = ArraySet(),
                    minimizedTasks = ArraySet(),
                    freeformTasksInZOrder = arrayListOf(1)
                )
            verify(persistentRepository)
                .addOrUpdateDesktop(
                    DEFAULT_USER_ID,
                    DEFAULT_DESKTOP_ID,
                    visibleTasks = ArraySet(),
                    minimizedTasks = ArraySet(),
                    freeformTasksInZOrder = ArrayList()
                )
        }
    }

    @Test
    fun removeFreeformTask_validDisplay_differentDisplay_doesNotRemovesTask() {
        repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        repo.removeFreeformTask(SECOND_DISPLAY, taskId = 1)

        val tasks = repo.getFreeformTasksInZOrder(DEFAULT_DISPLAY)
        assertThat(tasks).containsExactly(1)
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE)
    fun removeFreeformTask_validDisplayButDifferentDisplay_persistenceEnabled_doesNotRemoveTask() {
        runTest(StandardTestDispatcher()) {
            repo.addTask(DEFAULT_DISPLAY, taskId = 1, isVisible = true)

            repo.removeFreeformTask(SECOND_DISPLAY, taskId = 1)

            verify(persistentRepository)
                .addOrUpdateDesktop(
                    DEFAULT_USER_ID,
                    DEFAULT_DESKTOP_ID,
                    visibleTasks = ArraySet(),
                    minimizedTasks = ArraySet(),
                    freeformTasksInZOrder = arrayListOf(1)
                )
            verify(persistentRepository, never())
                .addOrUpdateDesktop(
                    DEFAULT_USER_ID,
                    DEFAULT_DESKTOP_ID,
                    visibleTasks = ArraySet(),
                    minimizedTasks = ArraySet(),
                    freeformTasksInZOrder = ArrayList()
                )
        }
    }

    @Test
    fun removeFreeformTask_removesTaskBoundsBeforeMaximize() {
        val taskId = 1
        repo.addTask(THIRD_DISPLAY, taskId, isVisible = true)
        repo.saveBoundsBeforeMaximize(taskId, Rect(0, 0, 200, 200))

        repo.removeFreeformTask(THIRD_DISPLAY, taskId)

        assertThat(repo.removeBoundsBeforeMaximize(taskId)).isNull()
    }

    @Test
    fun removeFreeformTask_removesTaskBoundsBeforeImmersive() {
        val taskId = 1
        repo.addTask(THIRD_DISPLAY, taskId, isVisible = true)
        repo.saveBoundsBeforeFullImmersive(taskId, Rect(0, 0, 200, 200))

        repo.removeFreeformTask(THIRD_DISPLAY, taskId)

        assertThat(repo.removeBoundsBeforeFullImmersive(taskId)).isNull()
    }

    @Test
    fun removeFreeformTask_removesActiveTask() {
        val taskId = 1
        val listener = TestListener()
        repo.addActiveTaskListener(listener)
        repo.addTask(DEFAULT_DISPLAY, taskId, isVisible = true)

        repo.removeFreeformTask(THIRD_DISPLAY, taskId)

        assertThat(repo.isActiveTask(taskId)).isFalse()
        assertThat(listener.activeChangesOnDefaultDisplay).isEqualTo(2)
    }

    @Test
    fun removeFreeformTask_unminimizesTask() {
        val taskId = 1
        repo.addTask(DEFAULT_DISPLAY, taskId, isVisible = true)
        repo.minimizeTask(DEFAULT_DISPLAY, taskId)

        repo.removeFreeformTask(DEFAULT_DISPLAY, taskId)

        assertThat(repo.isMinimizedTask(taskId)).isFalse()
    }

    @Test
    fun removeFreeformTask_updatesTaskVisibility() {
        val taskId = 1
        repo.addTask(DEFAULT_DISPLAY, taskId, isVisible = true)

        repo.removeFreeformTask(THIRD_DISPLAY, taskId)

        assertThat(repo.isVisibleTask(taskId)).isFalse()
    }

    @Test
    fun saveBoundsBeforeMaximize_boundsSavedByTaskId() {
        val taskId = 1
        val bounds = Rect(0, 0, 200, 200)

        repo.saveBoundsBeforeMaximize(taskId, bounds)

        assertThat(repo.removeBoundsBeforeMaximize(taskId)).isEqualTo(bounds)
    }

    @Test
    fun removeBoundsBeforeMaximize_returnsNullAfterBoundsRemoved() {
        val taskId = 1
        val bounds = Rect(0, 0, 200, 200)
        repo.saveBoundsBeforeMaximize(taskId, bounds)
        repo.removeBoundsBeforeMaximize(taskId)

        val boundsBeforeMaximize = repo.removeBoundsBeforeMaximize(taskId)

        assertThat(boundsBeforeMaximize).isNull()
    }

    @Test
    fun saveBoundsBeforeImmersive_boundsSavedByTaskId() {
        val taskId = 1
        val bounds = Rect(0, 0, 200, 200)

        repo.saveBoundsBeforeFullImmersive(taskId, bounds)

        assertThat(repo.removeBoundsBeforeFullImmersive(taskId)).isEqualTo(bounds)
    }

    @Test
    fun removeBoundsBeforeImmersive_returnsNullAfterBoundsRemoved() {
        val taskId = 1
        val bounds = Rect(0, 0, 200, 200)
        repo.saveBoundsBeforeFullImmersive(taskId, bounds)
        repo.removeBoundsBeforeFullImmersive(taskId)

        val boundsBeforeImmersive = repo.removeBoundsBeforeFullImmersive(taskId)

        assertThat(boundsBeforeImmersive).isNull()
    }

    @Test
    fun isMinimizedTask_minimizeTaskNotCalled_noTasksMinimized() {
        assertThat(repo.isMinimizedTask(taskId = 0)).isFalse()
        assertThat(repo.isMinimizedTask(taskId = 1)).isFalse()
    }

    @Test
    fun minimizeTask_minimizesCorrectTask() {
        repo.minimizeTask(displayId = 0, taskId = 0)

        assertThat(repo.isMinimizedTask(taskId = 0)).isTrue()
        assertThat(repo.isMinimizedTask(taskId = 1)).isFalse()
        assertThat(repo.isMinimizedTask(taskId = 2)).isFalse()
    }

    @Test
    fun minimizeTask_withInvalidDisplay_minimizesCorrectTask() {
        repo.addTask(displayId = DEFAULT_DISPLAY, taskId = 0, isVisible = true)

        repo.minimizeTask(displayId = INVALID_DISPLAY, taskId = 0)

        assertThat(repo.isMinimizedTask(taskId = 0)).isTrue()
        assertThat(repo.isMinimizedTask(taskId = 1)).isFalse()
        assertThat(repo.isMinimizedTask(taskId = 2)).isFalse()
    }

    @Test
    fun unminimizeTask_unminimizesTask() {
        repo.minimizeTask(displayId = 0, taskId = 0)

        repo.unminimizeTask(displayId = 0, taskId = 0)

        assertThat(repo.isMinimizedTask(taskId = 0)).isFalse()
        assertThat(repo.isMinimizedTask(taskId = 1)).isFalse()
        assertThat(repo.isMinimizedTask(taskId = 2)).isFalse()
    }

    @Test
    fun unminimizeTask_nonExistentTask_doesntCrash() {
        repo.unminimizeTask(displayId = 0, taskId = 0)

        // No change
        assertThat(repo.isMinimizedTask(taskId = 0)).isFalse()
        assertThat(repo.isMinimizedTask(taskId = 1)).isFalse()
        assertThat(repo.isMinimizedTask(taskId = 2)).isFalse()
    }


    @Test
    fun updateTask_minimizedTaskBecomesVisible_unminimizesTask() {
        repo.minimizeTask(displayId = 10, taskId = 2)
        repo.updateTask(displayId = 10, taskId = 2, isVisible = true)

        val isMinimizedTask = repo.isMinimizedTask(taskId = 2)

        assertThat(isMinimizedTask).isFalse()
    }

    @Test
    fun saveBoundsBeforeMinimize_boundsSavedByTaskId() {
        val taskId = 1
        val bounds = Rect(0, 0, 200, 200)

        repo.saveBoundsBeforeMinimize(taskId, bounds)

        assertThat(repo.removeBoundsBeforeMinimize(taskId)).isEqualTo(bounds)
    }

    @Test
    fun removeBoundsBeforeMinimize_returnsNullAfterBoundsRemoved() {
        val taskId = 1
        val bounds = Rect(0, 0, 200, 200)
        repo.saveBoundsBeforeMinimize(taskId, bounds)
        repo.removeBoundsBeforeMinimize(taskId)

        val boundsBeforeMinimize = repo.removeBoundsBeforeMinimize(taskId)

        assertThat(boundsBeforeMinimize).isNull()
    }

    @Test
    fun getExpandedTasksOrdered_returnsFreeformTasksInCorrectOrder() {
        repo.addTask(displayId = DEFAULT_DISPLAY, taskId = 3, isVisible = true)
        repo.addTask(displayId = DEFAULT_DISPLAY, taskId = 2, isVisible = true)
        repo.addTask(displayId = DEFAULT_DISPLAY, taskId = 1, isVisible = true)

        val tasks = repo.getExpandedTasksOrdered(displayId = 0)

        assertThat(tasks).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun getExpandedTasksOrdered_excludesMinimizedTasks() {
        repo.addTask(displayId = DEFAULT_DISPLAY, taskId = 3, isVisible = true)
        repo.addTask(displayId = DEFAULT_DISPLAY, taskId = 2, isVisible = true)
        repo.addTask(displayId = DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        repo.minimizeTask(displayId = DEFAULT_DISPLAY, taskId = 2)

        val tasks = repo.getExpandedTasksOrdered(displayId = DEFAULT_DISPLAY)

        assertThat(tasks).containsExactly(1, 3).inOrder()
    }

    @Test
    fun setTaskInFullImmersiveState_savedAsInImmersiveState() {
        assertThat(repo.isTaskInFullImmersiveState(taskId = 1)).isFalse()

        repo.setTaskInFullImmersiveState(DEFAULT_DESKTOP_ID, taskId = 1, immersive = true)

        assertThat(repo.isTaskInFullImmersiveState(taskId = 1)).isTrue()
    }

    @Test
    fun removeTaskInFullImmersiveState_removedAsInImmersiveState() {
        repo.setTaskInFullImmersiveState(DEFAULT_DESKTOP_ID, taskId = 1, immersive = true)
        assertThat(repo.isTaskInFullImmersiveState(taskId = 1)).isTrue()

        repo.setTaskInFullImmersiveState(DEFAULT_DESKTOP_ID, taskId = 1, immersive = false)

        assertThat(repo.isTaskInFullImmersiveState(taskId = 1)).isFalse()
    }

    @Test
    fun removeTaskInFullImmersiveState_otherWasImmersive_otherRemainsImmersive() {
        repo.setTaskInFullImmersiveState(DEFAULT_DESKTOP_ID, taskId = 1, immersive = true)

        repo.setTaskInFullImmersiveState(DEFAULT_DESKTOP_ID, taskId = 2, immersive = false)

        assertThat(repo.isTaskInFullImmersiveState(taskId = 1)).isTrue()
    }

    @Test
    fun setTaskInFullImmersiveState_sameDisplay_overridesExistingFullImmersiveTask() {
        repo.setTaskInFullImmersiveState(DEFAULT_DESKTOP_ID, taskId = 1, immersive = true)
        repo.setTaskInFullImmersiveState(DEFAULT_DESKTOP_ID, taskId = 2, immersive = true)

        assertThat(repo.isTaskInFullImmersiveState(taskId = 1)).isFalse()
        assertThat(repo.isTaskInFullImmersiveState(taskId = 2)).isTrue()
    }

    @Test
    fun setTaskInFullImmersiveState_differentDisplay_bothAreImmersive() {
        repo.setTaskInFullImmersiveState(DEFAULT_DESKTOP_ID, taskId = 1, immersive = true)
        repo.setTaskInFullImmersiveState(DEFAULT_DESKTOP_ID + 1, taskId = 2, immersive = true)

        assertThat(repo.isTaskInFullImmersiveState(taskId = 1)).isTrue()
        assertThat(repo.isTaskInFullImmersiveState(taskId = 2)).isTrue()
    }

    @Test
    fun removeDesktop_multipleTasks_removesAll() {
        // The front-most task will be the one added last through `addTask`.
        repo.addTask(displayId = DEFAULT_DISPLAY, taskId = 3, isVisible = true)
        repo.addTask(displayId = DEFAULT_DISPLAY, taskId = 2, isVisible = true)
        repo.addTask(displayId = DEFAULT_DISPLAY, taskId = 1, isVisible = true)
        repo.minimizeTask(displayId = DEFAULT_DISPLAY, taskId = 2)

        val tasksBeforeRemoval = repo.removeDesktop(displayId = DEFAULT_DISPLAY)

        assertThat(tasksBeforeRemoval).containsExactly(1, 2, 3).inOrder()
        assertThat(repo.getActiveTasks(displayId = DEFAULT_DISPLAY)).isEmpty()
    }

    @Test
    fun getTaskInFullImmersiveState_byDisplay() {
        repo.setTaskInFullImmersiveState(DEFAULT_DESKTOP_ID, taskId = 1, immersive = true)
        repo.setTaskInFullImmersiveState(DEFAULT_DESKTOP_ID + 1, taskId = 2, immersive = true)

        assertThat(repo.getTaskInFullImmersiveState(DEFAULT_DESKTOP_ID)).isEqualTo(1)
        assertThat(repo.getTaskInFullImmersiveState(DEFAULT_DESKTOP_ID + 1)).isEqualTo(2)
    }

    class TestListener : DesktopRepository.ActiveTasksListener {
        var activeChangesOnDefaultDisplay = 0
        var activeChangesOnSecondaryDisplay = 0
        override fun onActiveTasksChanged(displayId: Int) {
            when (displayId) {
                DEFAULT_DISPLAY -> activeChangesOnDefaultDisplay++
                SECOND_DISPLAY -> activeChangesOnSecondaryDisplay++
                else -> fail("Active task listener received unexpected display id: $displayId")
            }
        }
    }

    class TestVisibilityListener : DesktopRepository.VisibleTasksListener {
        var visibleTasksCountOnDefaultDisplay = 0
        var visibleTasksCountOnSecondaryDisplay = 0

        var visibleChangesOnDefaultDisplay = 0
        var visibleChangesOnSecondaryDisplay = 0

        override fun onTasksVisibilityChanged(displayId: Int, visibleTasksCount: Int) {
            when (displayId) {
                DEFAULT_DISPLAY -> {
                    visibleTasksCountOnDefaultDisplay = visibleTasksCount
                    visibleChangesOnDefaultDisplay++
                }
                SECOND_DISPLAY -> {
                    visibleTasksCountOnSecondaryDisplay = visibleTasksCount
                    visibleChangesOnSecondaryDisplay++
                }
                else -> fail("Visible task listener received unexpected display id: $displayId")
            }
        }
    }

    companion object {
        const val SECOND_DISPLAY = 1
        const val THIRD_DISPLAY = 345
        private const val DEFAULT_USER_ID = 1000
        private const val DEFAULT_DESKTOP_ID = 0
    }
}
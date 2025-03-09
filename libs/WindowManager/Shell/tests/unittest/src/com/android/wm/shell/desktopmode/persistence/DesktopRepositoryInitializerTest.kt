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

package com.android.wm.shell.desktopmode.persistence

import android.os.UserManager
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import android.view.Display.DEFAULT_DISPLAY
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_HSUM
import com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.desktopmode.DesktopUserRepositories
import com.android.wm.shell.sysui.ShellInit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.spy
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever


@SmallTest
@RunWith(AndroidTestingRunner::class)
@ExperimentalCoroutinesApi
class DesktopRepositoryInitializerTest : ShellTestCase() {

    @JvmField
    @Rule
    val setFlagsRule = SetFlagsRule()

    private lateinit var repositoryInitializer: DesktopRepositoryInitializer
    private lateinit var shellInit: ShellInit
    private lateinit var datastoreScope: CoroutineScope

    private lateinit var desktopUserRepositories: DesktopUserRepositories
    private val persistentRepository = mock<DesktopPersistentRepository>()
    private val userManager = mock<UserManager>()
    private val testExecutor = mock<ShellExecutor>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        shellInit = spy(ShellInit(testExecutor))
        datastoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        repositoryInitializer =
            DesktopRepositoryInitializerImpl(context, persistentRepository, datastoreScope)
        desktopUserRepositories =
            DesktopUserRepositories(
                context, shellInit, persistentRepository, repositoryInitializer, datastoreScope,
                userManager
            )
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE, FLAG_ENABLE_DESKTOP_WINDOWING_HSUM)
    fun initWithPersistence_multipleUsers_addedCorrectly() =
        runTest(StandardTestDispatcher()) {
            whenever(persistentRepository.getUserDesktopRepositoryMap()).thenReturn(
                mapOf(
                    USER_ID_1 to desktopRepositoryState1,
                    USER_ID_2 to desktopRepositoryState2
                )
            )
            whenever(persistentRepository.getDesktopRepositoryState(USER_ID_1))
                .thenReturn(desktopRepositoryState1)
            whenever(persistentRepository.getDesktopRepositoryState(USER_ID_2))
                .thenReturn(desktopRepositoryState2)
            whenever(persistentRepository.readDesktop(USER_ID_1, DESKTOP_ID_1))
                .thenReturn(desktop1)
            whenever(persistentRepository.readDesktop(USER_ID_1, DESKTOP_ID_2))
                .thenReturn(desktop2)
            whenever(persistentRepository.readDesktop(USER_ID_2, DESKTOP_ID_3))
                .thenReturn(desktop3)

            repositoryInitializer.initialize(desktopUserRepositories)

            // Desktop Repository currently returns all tasks across desktops for a specific user
            // since the repository currently doesn't handle desktops. This test logic should be updated
            // once the repository handles multiple desktops.
            assertThat(
                desktopUserRepositories.getProfile(USER_ID_1)
                    .getActiveTasks(DEFAULT_DISPLAY)
            )
                .containsExactly(1, 3, 4, 5)
                .inOrder()
            assertThat(
                desktopUserRepositories.getProfile(USER_ID_1)
                    .getExpandedTasksOrdered(DEFAULT_DISPLAY)
            )
                .containsExactly(5, 1)
                .inOrder()
            assertThat(
                desktopUserRepositories.getProfile(USER_ID_1)
                    .getMinimizedTasks(DEFAULT_DISPLAY)
            )
                .containsExactly(3, 4)
                .inOrder()

            assertThat(
                desktopUserRepositories.getProfile(USER_ID_2)
                    .getActiveTasks(DEFAULT_DISPLAY)
            )
                .containsExactly(7, 8)
                .inOrder()
            assertThat(
                desktopUserRepositories.getProfile(USER_ID_2)
                    .getExpandedTasksOrdered(DEFAULT_DISPLAY)
            )
                .contains(7)
            assertThat(
                desktopUserRepositories.getProfile(USER_ID_2)
                    .getMinimizedTasks(DEFAULT_DISPLAY)
            ).containsExactly(8)
        }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE)
    fun initWithPersistence_singleUser_addedCorrectly() =
        runTest(StandardTestDispatcher()) {
            whenever(persistentRepository.getUserDesktopRepositoryMap()).thenReturn(
                mapOf(
                    USER_ID_1 to desktopRepositoryState1,
                )
            )
            whenever(persistentRepository.getDesktopRepositoryState(USER_ID_1))
                .thenReturn(desktopRepositoryState1)
            whenever(persistentRepository.readDesktop(USER_ID_1, DESKTOP_ID_1))
                .thenReturn(desktop1)
            whenever(persistentRepository.readDesktop(USER_ID_1, DESKTOP_ID_2))
                .thenReturn(desktop2)

            repositoryInitializer.initialize(desktopUserRepositories)

            // Desktop Repository currently returns all tasks across desktops for a specific user
            // since the repository currently doesn't handle desktops. This test logic should be updated
            // once the repository handles multiple desktops.
            assertThat(
                desktopUserRepositories.getProfile(USER_ID_1)
                    .getActiveTasks(DEFAULT_DISPLAY)
            )
                .containsExactly(1, 3, 4, 5)
                .inOrder()
            assertThat(
                desktopUserRepositories.getProfile(USER_ID_1)
                    .getExpandedTasksOrdered(DEFAULT_DISPLAY)
            )
                .containsExactly(5, 1)
                .inOrder()
            assertThat(
                desktopUserRepositories.getProfile(USER_ID_1)
                    .getMinimizedTasks(DEFAULT_DISPLAY)
            )
                .containsExactly(3, 4)
                .inOrder()
        }

    @After
    fun tearDown() {
        datastoreScope.cancel()
    }

    private companion object {
        const val USER_ID_1 = 5
        const val USER_ID_2 = 6
        const val DESKTOP_ID_1 = 2
        const val DESKTOP_ID_2 = 3
        const val DESKTOP_ID_3 = 4

        val freeformTasksInZOrder1 = listOf(1, 3)
        val desktop1: Desktop = Desktop.newBuilder()
            .setDesktopId(DESKTOP_ID_1)
            .addAllZOrderedTasks(freeformTasksInZOrder1)
            .putTasksByTaskId(
                1,
                DesktopTask.newBuilder()
                    .setTaskId(1)
                    .setDesktopTaskState(DesktopTaskState.VISIBLE)
                    .build()
            )
            .putTasksByTaskId(
                3,
                DesktopTask.newBuilder()
                    .setTaskId(3)
                    .setDesktopTaskState(DesktopTaskState.MINIMIZED)
                    .build()
            )
            .build()

        val freeformTasksInZOrder2 = listOf(4, 5)
        val desktop2: Desktop = Desktop.newBuilder()
            .setDesktopId(DESKTOP_ID_2)
            .addAllZOrderedTasks(freeformTasksInZOrder2)
            .putTasksByTaskId(
                4,
                DesktopTask.newBuilder()
                    .setTaskId(4)
                    .setDesktopTaskState(DesktopTaskState.MINIMIZED)
                    .build()
            )
            .putTasksByTaskId(
                5,
                DesktopTask.newBuilder()
                    .setTaskId(5)
                    .setDesktopTaskState(DesktopTaskState.VISIBLE)
                    .build()
            )
            .build()

        val freeformTasksInZOrder3 = listOf(7, 8)
        val desktop3: Desktop = Desktop.newBuilder()
            .setDesktopId(DESKTOP_ID_3)
            .addAllZOrderedTasks(freeformTasksInZOrder3)
            .putTasksByTaskId(
                7,
                DesktopTask.newBuilder()
                    .setTaskId(7)
                    .setDesktopTaskState(DesktopTaskState.VISIBLE)
                    .build()
            )
            .putTasksByTaskId(
                8,
                DesktopTask.newBuilder()
                    .setTaskId(8)
                    .setDesktopTaskState(DesktopTaskState.MINIMIZED)
                    .build()
            )
            .build()
        val desktopRepositoryState1: DesktopRepositoryState = DesktopRepositoryState.newBuilder()
            .putDesktop(DESKTOP_ID_1, desktop1)
            .putDesktop(DESKTOP_ID_2, desktop2)
            .build()
        val desktopRepositoryState2: DesktopRepositoryState = DesktopRepositoryState.newBuilder()
            .putDesktop(DESKTOP_ID_3, desktop3)
            .build()
    }
}

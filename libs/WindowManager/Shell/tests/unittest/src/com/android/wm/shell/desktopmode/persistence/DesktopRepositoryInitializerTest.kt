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

import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import android.view.Display.DEFAULT_DISPLAY
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.desktopmode.DesktopRepository
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
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.spy
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@ExperimentalCoroutinesApi
class DesktopRepositoryInitializerTest : ShellTestCase() {

    private lateinit var repositoryInitializer: DesktopRepositoryInitializer
    private lateinit var shellInit: ShellInit
    private lateinit var datastoreScope: CoroutineScope

    private lateinit var desktopRepository: DesktopRepository
    private val persistentRepository = mock<DesktopPersistentRepository>()
    private val testExecutor = mock<ShellExecutor>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        shellInit = spy(ShellInit(testExecutor))
        datastoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        repositoryInitializer =
            DesktopRepositoryInitializerImpl(context, persistentRepository, datastoreScope)
        desktopRepository =
            DesktopRepository(
                context, shellInit, persistentRepository, repositoryInitializer, datastoreScope)
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE)
    fun initWithPersistence_multipleTasks_addedCorrectly() =
        runTest(StandardTestDispatcher()) {
            val freeformTasksInZOrder = listOf(1, 2, 3)
            whenever(persistentRepository.readDesktop(any(), any()))
                .thenReturn(
                    Desktop.newBuilder()
                        .setDesktopId(1)
                        .addAllZOrderedTasks(freeformTasksInZOrder)
                        .putTasksByTaskId(
                            1,
                            DesktopTask.newBuilder()
                                .setTaskId(1)
                                .setDesktopTaskState(DesktopTaskState.VISIBLE)
                                .build())
                        .putTasksByTaskId(
                            2,
                            DesktopTask.newBuilder()
                                .setTaskId(2)
                                .setDesktopTaskState(DesktopTaskState.VISIBLE)
                                .build())
                        .putTasksByTaskId(
                            3,
                            DesktopTask.newBuilder()
                                .setTaskId(3)
                                .setDesktopTaskState(DesktopTaskState.MINIMIZED)
                                .build())
                        .build())

            repositoryInitializer.initialize(desktopRepository)

            verify(persistentRepository).readDesktop(any(), any())
            assertThat(desktopRepository.getActiveTasks(DEFAULT_DISPLAY))
                .containsExactly(1, 2, 3)
                .inOrder()
            assertThat(desktopRepository.getExpandedTasksOrdered(DEFAULT_DISPLAY))
                .containsExactly(1, 2)
                .inOrder()
            assertThat(desktopRepository.getMinimizedTasks(DEFAULT_DISPLAY)).containsExactly(3)
        }

    @After
    fun tearDown() {
        datastoreScope.cancel()
    }
}

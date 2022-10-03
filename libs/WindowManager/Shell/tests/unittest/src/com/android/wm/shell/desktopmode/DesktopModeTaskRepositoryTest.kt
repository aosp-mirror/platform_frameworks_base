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
        repo.addListener(listener)

        repo.addActiveTask(1)
        assertThat(listener.activeTaskChangedCalls).isEqualTo(1)
        assertThat(repo.isActiveTask(1)).isTrue()
    }

    @Test
    fun addActiveTask_sameTaskDoesNotNotify() {
        val listener = TestListener()
        repo.addListener(listener)

        repo.addActiveTask(1)
        repo.addActiveTask(1)
        assertThat(listener.activeTaskChangedCalls).isEqualTo(1)
    }

    @Test
    fun addActiveTask_multipleTasksAddedNotifiesForEach() {
        val listener = TestListener()
        repo.addListener(listener)

        repo.addActiveTask(1)
        repo.addActiveTask(2)
        assertThat(listener.activeTaskChangedCalls).isEqualTo(2)
    }

    @Test
    fun removeActiveTask_listenerNotifiedAndTaskNotActive() {
        val listener = TestListener()
        repo.addListener(listener)

        repo.addActiveTask(1)
        repo.removeActiveTask(1)
        // Notify once for add and once for remove
        assertThat(listener.activeTaskChangedCalls).isEqualTo(2)
        assertThat(repo.isActiveTask(1)).isFalse()
    }

    @Test
    fun removeActiveTask_removeNotExistingTaskDoesNotNotify() {
        val listener = TestListener()
        repo.addListener(listener)
        repo.removeActiveTask(99)
        assertThat(listener.activeTaskChangedCalls).isEqualTo(0)
    }

    @Test
    fun isActiveTask_notExistingTaskReturnsFalse() {
        assertThat(repo.isActiveTask(99)).isFalse()
    }

    class TestListener : DesktopModeTaskRepository.Listener {
        var activeTaskChangedCalls = 0
        override fun onActiveTasksChanged() {
            activeTaskChangedCalls++
        }
    }
}

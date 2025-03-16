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
package com.android.wm.shell.draganddrop

import android.app.ActivityTaskManager
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW
import android.app.WindowConfiguration.WINDOWING_MODE_PINNED
import android.content.ClipDescription
import android.content.ClipDescription.EXTRA_HIDE_DRAG_SOURCE_TASK_ID
import android.os.PersistableBundle
import android.os.RemoteException
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.draganddrop.DragTestUtils.createTaskInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * Tests for DragSession.
 *
 * Usage: atest WMShellUnitTests:DragSessionTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class DragSessionTest : ShellTestCase() {
    @Mock
    private lateinit var activityTaskManager: ActivityTaskManager

    @Mock
    private lateinit var displayLayout: DisplayLayout

    @Before
    @Throws(RemoteException::class)
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testNullClipData() {
        // Start a new drag session with null data
        val session = DragSession(activityTaskManager, displayLayout, null, 0)
        assertThat(session.hideDragSourceTaskId).isEqualTo(-1)
    }

    @Test
    fun testGetRunningTask() {
        // Set up running tasks
        val runningTasks = listOf(
            createTaskInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD),
        )
        whenever(activityTaskManager.getTasks(any(), any())).thenReturn(runningTasks)

        // Simulate dragging an app
        val data = DragTestUtils.createAppClipData(ClipDescription.MIMETYPE_APPLICATION_SHORTCUT)

        // Start a new drag session
        val session = DragSession(activityTaskManager, displayLayout, data, 0)
        session.updateRunningTask()

        assertThat(session.runningTaskInfo).isEqualTo(runningTasks.first())
        assertThat(session.runningTaskWinMode).isEqualTo(runningTasks.first().windowingMode)
        assertThat(session.runningTaskActType).isEqualTo(runningTasks.first().activityType)
    }

    @Test
    fun testGetRunningTaskWithFloatingTasks() {
        // Set up running tasks
        val runningTasks = listOf(
            createTaskInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD),
            createTaskInfo(WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD),
            createTaskInfo(WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD, alwaysOnTop=true),
        )
        whenever(activityTaskManager.getTasks(any(), any())).thenReturn(runningTasks)

        // Simulate dragging an app
        val data = DragTestUtils.createAppClipData(ClipDescription.MIMETYPE_APPLICATION_SHORTCUT)

        // Start a new drag session
        val session = DragSession(activityTaskManager, displayLayout, data, 0)
        session.updateRunningTask()

        // Ensure that we find the first non-floating task
        assertThat(session.runningTaskInfo).isEqualTo(runningTasks.first())
        assertThat(session.runningTaskWinMode).isEqualTo(runningTasks.first().windowingMode)
        assertThat(session.runningTaskActType).isEqualTo(runningTasks.first().activityType)
    }

    @Test
    fun testHideDragSource_readDragFlag() {
        // Set up running tasks
        val runningTasks = listOf(
            createTaskInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD),
            createTaskInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD),
        )
        whenever(activityTaskManager.getTasks(any(), any())).thenReturn(runningTasks)

        // Simulate dragging an app with hide-drag-source set for the second (top most) app
        val data = DragTestUtils.createAppClipData(ClipDescription.MIMETYPE_APPLICATION_SHORTCUT)
        data.description.extras =
            PersistableBundle().apply {
                putInt(
                    EXTRA_HIDE_DRAG_SOURCE_TASK_ID,
                    runningTasks.last().taskId
                )
            }

        // Start a new drag session
        val session = DragSession(activityTaskManager, displayLayout, data, 0)
        session.updateRunningTask()

        assertThat(session.hideDragSourceTaskId).isEqualTo(runningTasks.last().taskId)
    }
}

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
package com.android.wm.shell.desktopmode.multidesks

import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.testing.AndroidTestingRunner
import android.view.Display
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.TransitionInfo
import android.window.WindowContainerTransaction
import android.window.WindowContainerTransaction.HierarchyOp
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.desktopmode.DesktopTestHelpers.createFreeformTask
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellInit
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

/**
 * Tests for [RootTaskDesksOrganizer].
 *
 * Usage: atest WMShellUnitTests:RootTaskDesksOrganizerTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class RootTaskDesksOrganizerTest : ShellTestCase() {

    private val testExecutor = TestShellExecutor()
    private val testShellInit = ShellInit(testExecutor)
    private val mockShellCommandHandler = mock<ShellCommandHandler>()
    private val mockShellTaskOrganizer = mock<ShellTaskOrganizer>()

    private lateinit var organizer: RootTaskDesksOrganizer

    @Before
    fun setUp() {
        organizer =
            RootTaskDesksOrganizer(testShellInit, mockShellCommandHandler, mockShellTaskOrganizer)
    }

    @Test
    fun testCreateDesk_callsBack() {
        val callback = FakeOnCreateCallback()
        organizer.createDesk(Display.DEFAULT_DISPLAY, callback)

        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }
        organizer.onTaskAppeared(freeformRoot, SurfaceControl())

        assertThat(callback.created).isTrue()
        assertEquals(freeformRoot.taskId, callback.deskId)
    }

    @Test
    fun testOnTaskAppeared_withoutRequest_throws() {
        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }

        assertThrows(Exception::class.java) {
            organizer.onTaskAppeared(freeformRoot, SurfaceControl())
        }
    }

    @Test
    fun testOnTaskAppeared_withRequestOnlyInAnotherDisplay_throws() {
        organizer.createDesk(displayId = 2, FakeOnCreateCallback())
        val freeformRoot = createFreeformTask(Display.DEFAULT_DISPLAY).apply { parentTaskId = -1 }

        assertThrows(Exception::class.java) {
            organizer.onTaskAppeared(freeformRoot, SurfaceControl())
        }
    }

    @Test
    fun testOnTaskAppeared_duplicateRoot_throws() {
        organizer.createDesk(Display.DEFAULT_DISPLAY, FakeOnCreateCallback())
        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }
        organizer.onTaskAppeared(freeformRoot, SurfaceControl())

        assertThrows(Exception::class.java) {
            organizer.onTaskAppeared(freeformRoot, SurfaceControl())
        }
    }

    @Test
    fun testOnTaskVanished_removesRoot() {
        val callback = FakeOnCreateCallback()
        organizer.createDesk(Display.DEFAULT_DISPLAY, callback)
        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }
        organizer.onTaskAppeared(freeformRoot, SurfaceControl())

        organizer.onTaskVanished(freeformRoot)

        assertThat(organizer.roots.contains(freeformRoot.taskId)).isFalse()
    }

    @Test
    fun testDesktopWindowAppearsInDesk() {
        organizer.createDesk(Display.DEFAULT_DISPLAY, FakeOnCreateCallback())
        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }
        organizer.onTaskAppeared(freeformRoot, SurfaceControl())
        val child = createFreeformTask().apply { parentTaskId = freeformRoot.taskId }

        organizer.onTaskAppeared(child, SurfaceControl())

        assertThat(organizer.roots[freeformRoot.taskId].children).contains(child.taskId)
    }

    @Test
    fun testDesktopWindowDisappearsFromDesk() {
        organizer.createDesk(Display.DEFAULT_DISPLAY, FakeOnCreateCallback())
        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }
        organizer.onTaskAppeared(freeformRoot, SurfaceControl())
        val child = createFreeformTask().apply { parentTaskId = freeformRoot.taskId }

        organizer.onTaskAppeared(child, SurfaceControl())
        organizer.onTaskVanished(child)

        assertThat(organizer.roots[freeformRoot.taskId].children).doesNotContain(child.taskId)
    }

    @Test
    fun testRemoveDesk() {
        organizer.createDesk(Display.DEFAULT_DISPLAY, FakeOnCreateCallback())
        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }
        organizer.onTaskAppeared(freeformRoot, SurfaceControl())

        val wct = WindowContainerTransaction()
        organizer.removeDesk(wct, freeformRoot.taskId)

        assertThat(
                wct.hierarchyOps.any { hop ->
                    hop.type == HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_ROOT_TASK &&
                        hop.container == freeformRoot.token.asBinder()
                }
            )
            .isTrue()
    }

    @Test
    fun testRemoveDesk_didNotExist_throws() {
        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }

        val wct = WindowContainerTransaction()
        assertThrows(Exception::class.java) { organizer.removeDesk(wct, freeformRoot.taskId) }
    }

    @Test
    fun testActivateDesk() {
        organizer.createDesk(Display.DEFAULT_DISPLAY, FakeOnCreateCallback())
        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }
        organizer.onTaskAppeared(freeformRoot, SurfaceControl())

        val wct = WindowContainerTransaction()
        organizer.activateDesk(wct, freeformRoot.taskId)

        assertThat(
                wct.hierarchyOps.any { hop ->
                    hop.type == HierarchyOp.HIERARCHY_OP_TYPE_REORDER &&
                        hop.toTop &&
                        hop.container == freeformRoot.token.asBinder()
                }
            )
            .isTrue()
        assertThat(
                wct.hierarchyOps.any { hop ->
                    hop.type == HierarchyOp.HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT &&
                        hop.container == freeformRoot.token.asBinder()
                }
            )
            .isTrue()
    }

    @Test
    fun testActivateDesk_didNotExist_throws() {
        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }

        val wct = WindowContainerTransaction()
        assertThrows(Exception::class.java) { organizer.activateDesk(wct, freeformRoot.taskId) }
    }

    @Test
    fun testMoveTaskToDesk() {
        organizer.createDesk(Display.DEFAULT_DISPLAY, FakeOnCreateCallback())
        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }
        organizer.onTaskAppeared(freeformRoot, SurfaceControl())

        val desktopTask = createFreeformTask().apply { parentTaskId = -1 }
        val wct = WindowContainerTransaction()
        organizer.moveTaskToDesk(wct, freeformRoot.taskId, desktopTask)

        assertThat(
                wct.hierarchyOps.any { hop ->
                    hop.isReparent &&
                        hop.toTop &&
                        hop.container == desktopTask.token.asBinder() &&
                        hop.newParent == freeformRoot.token.asBinder()
                }
            )
            .isTrue()
        assertThat(
                wct.changes.any { change ->
                    change.key == desktopTask.token.asBinder() &&
                        change.value.windowingMode == WINDOWING_MODE_UNDEFINED
                }
            )
            .isTrue()
    }

    @Test
    fun testMoveTaskToDesk_didNotExist_throws() {
        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }

        val desktopTask = createFreeformTask().apply { parentTaskId = -1 }
        val wct = WindowContainerTransaction()
        assertThrows(Exception::class.java) {
            organizer.moveTaskToDesk(wct, freeformRoot.taskId, desktopTask)
        }
    }

    @Test
    fun testGetDeskAtEnd() {
        organizer.createDesk(Display.DEFAULT_DISPLAY, FakeOnCreateCallback())
        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }
        organizer.onTaskAppeared(freeformRoot, SurfaceControl())

        val task = createFreeformTask().apply { parentTaskId = freeformRoot.taskId }
        val endDesk =
            organizer.getDeskAtEnd(
                TransitionInfo.Change(task.token, SurfaceControl()).apply { taskInfo = task }
            )

        assertThat(endDesk).isEqualTo(freeformRoot.taskId)
    }

    @Test
    fun testIsDeskActiveAtEnd() {
        organizer.createDesk(Display.DEFAULT_DISPLAY, FakeOnCreateCallback())
        val freeformRoot = createFreeformTask().apply { parentTaskId = -1 }
        freeformRoot.isVisibleRequested = true
        organizer.onTaskAppeared(freeformRoot, SurfaceControl())

        val isActive =
            organizer.isDeskActiveAtEnd(
                change =
                    TransitionInfo.Change(freeformRoot.token, SurfaceControl()).apply {
                        taskInfo = freeformRoot
                        mode = TRANSIT_TO_FRONT
                    },
                deskId = freeformRoot.taskId,
            )

        assertThat(isActive).isTrue()
    }

    private class FakeOnCreateCallback : DesksOrganizer.OnCreateCallback {
        var deskId: Int? = null
        val created: Boolean
            get() = deskId != null

        override fun onCreated(deskId: Int) {
            this.deskId = deskId
        }
    }
}

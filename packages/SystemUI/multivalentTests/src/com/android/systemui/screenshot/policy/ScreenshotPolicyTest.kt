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

package com.android.systemui.screenshot.policy

import android.content.ComponentName
import android.graphics.Rect
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.ActivityNames.FILES
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.ActivityNames.MESSAGES
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.ActivityNames.YOUTUBE
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.Bounds.FREEFORM_FULL_SCREEN
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.Bounds.FULL_SCREEN
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.Orientation.VERTICAL
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.TaskSpec
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.freeFormApps
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.pictureInPictureApp
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.singleFullScreen
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.splitScreenApps
import com.android.systemui.screenshot.data.model.allTasks
import com.android.systemui.screenshot.data.repository.profileTypeRepository
import com.android.systemui.screenshot.policy.CaptureType.FullScreen
import com.android.systemui.screenshot.policy.CaptureType.IsolatedTask
import com.android.systemui.screenshot.policy.TestUserIds.PERSONAL
import com.android.systemui.screenshot.policy.TestUserIds.PRIVATE
import com.android.systemui.screenshot.policy.TestUserIds.WORK
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotPolicyTest {
    private val kosmos = Kosmos()

    private val defaultComponent = ComponentName("default", "default")
    private val defaultOwner = UserHandle.SYSTEM
    private val policy = ScreenshotPolicy(kosmos.profileTypeRepository)

    @Test
    fun fullScreen_work() = runTest {
        val displayContent = singleFullScreen(TaskSpec(taskId = 1002, name = FILES, userId = WORK))
        val expectedFocusedTask =
            displayContent.rootTasks.first().childTasksTopDown().single { it.id == 1002 }

        val result = policy.apply(displayContent, defaultComponent, defaultOwner)

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = IsolatedTask(taskId = 1002, taskBounds = FULL_SCREEN),
                    contentTask =
                        TaskReference(
                            taskId = expectedFocusedTask.id,
                            component = expectedFocusedTask.componentName,
                            owner = UserHandle.of(expectedFocusedTask.userId),
                            bounds = expectedFocusedTask.bounds,
                        ),
                    owner = UserHandle.of(expectedFocusedTask.userId),
                )
            )
    }

    @Test
    fun fullScreen_private() = runTest {
        val displayContent =
            singleFullScreen(TaskSpec(taskId = 1002, name = YOUTUBE, userId = PRIVATE))
        val expectedFocusedTask =
            displayContent.rootTasks.first().childTasksTopDown().single { it.id == 1002 }

        val result = policy.apply(displayContent, defaultComponent, defaultOwner)

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    contentTask =
                        TaskReference(
                            taskId = expectedFocusedTask.id,
                            component = expectedFocusedTask.componentName,
                            owner = UserHandle.of(expectedFocusedTask.userId),
                            bounds = expectedFocusedTask.bounds,
                        ),
                    owner = UserHandle.of(expectedFocusedTask.userId),
                )
            )
    }

    @Test
    fun splitScreen_workAndPersonal() = runTest {
        val displayContent =
            splitScreenApps(
                first = TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                second = TaskSpec(taskId = 1003, name = YOUTUBE, userId = PERSONAL),
                focusedTaskId = 1002,
            )
        val expectedFocusedTask =
            displayContent.rootTasks.first().childTasksTopDown().single { it.id == 1002 }

        val result = policy.apply(displayContent, defaultComponent, defaultOwner)

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    contentTask =
                        TaskReference(
                            taskId = expectedFocusedTask.id,
                            component = expectedFocusedTask.componentName,
                            owner = UserHandle.of(expectedFocusedTask.userId),
                            bounds = expectedFocusedTask.bounds,
                        ),
                    owner = UserHandle.of(PERSONAL),
                )
            )
    }

    @Test
    fun splitScreen_personalAndPrivate() = runTest {
        val displayContent =
            splitScreenApps(
                first = TaskSpec(taskId = 1002, name = FILES, userId = PERSONAL),
                second = TaskSpec(taskId = 1003, name = YOUTUBE, userId = PRIVATE),
                focusedTaskId = 1002,
            )
        val expectedFocusedTask =
            displayContent.rootTasks.first().childTasksTopDown().single { it.id == 1002 }

        val result = policy.apply(displayContent, defaultComponent, defaultOwner)

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    contentTask =
                        TaskReference(
                            taskId = expectedFocusedTask.id,
                            component = expectedFocusedTask.componentName,
                            owner = UserHandle.of(expectedFocusedTask.userId),
                            bounds = expectedFocusedTask.bounds,
                        ),
                    owner = UserHandle.of(PRIVATE),
                )
            )
    }

    @Test
    fun splitScreen_workAndPrivate() = runTest {
        val displayContent =
            splitScreenApps(
                first = TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                second = TaskSpec(taskId = 1003, name = YOUTUBE, userId = PRIVATE),
                focusedTaskId = 1002,
            )
        val expectedFocusedTask =
            displayContent.rootTasks.first().childTasksTopDown().single { it.id == 1002 }

        val result = policy.apply(displayContent, defaultComponent, defaultOwner)

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    contentTask =
                        TaskReference(
                            taskId = expectedFocusedTask.id,
                            component = expectedFocusedTask.componentName,
                            owner = UserHandle.of(expectedFocusedTask.userId),
                            bounds = expectedFocusedTask.bounds,
                        ),
                    owner = UserHandle.of(PRIVATE),
                )
            )
    }

    @Test
    fun splitScreen_twoWorkTasks() = runTest {
        val displayContent =
            splitScreenApps(
                parentTaskId = 1,
                parentBounds = FREEFORM_FULL_SCREEN,
                orientation = VERTICAL,
                first = TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                second = TaskSpec(taskId = 1003, name = YOUTUBE, userId = WORK),
                focusedTaskId = 1002,
            )
        val expectedFocusedTask =
            displayContent.rootTasks.first().childTasksTopDown().single { it.id == 1002 }

        val result = policy.apply(displayContent, defaultComponent, defaultOwner)

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = IsolatedTask(taskBounds = FREEFORM_FULL_SCREEN, taskId = 1),
                    contentTask =
                        TaskReference(
                            taskId = expectedFocusedTask.id,
                            component = expectedFocusedTask.componentName,
                            owner = UserHandle.of(expectedFocusedTask.userId),
                            bounds = expectedFocusedTask.bounds,
                        ),
                    owner = UserHandle.of(WORK),
                )
            )
    }

    @Test
    fun freeform_floatingWindows() = runTest {
        val displayContent =
            freeFormApps(
                TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                TaskSpec(taskId = 1003, name = YOUTUBE, userId = PERSONAL),
                focusedTaskId = 1003,
            )
        val expectedFocusedTask =
            displayContent.rootTasks.first().childTasksTopDown().single { it.id == 1003 }

        val result = policy.apply(displayContent, defaultComponent, defaultOwner)

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    contentTask =
                        TaskReference(
                            taskId = expectedFocusedTask.id,
                            component = expectedFocusedTask.componentName,
                            owner = UserHandle.of(expectedFocusedTask.userId),
                            bounds = expectedFocusedTask.bounds,
                        ),
                    owner = UserHandle.of(PERSONAL),
                )
            )
    }

    @Test
    fun freeform_floatingWindows_work_maximized() = runTest {
        val displayContent =
            freeFormApps(
                TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                TaskSpec(taskId = 1003, name = YOUTUBE, userId = PERSONAL),
                focusedTaskId = 1002,
                maximizedTaskId = 1002,
            )
        val expectedFocusedTask =
            displayContent.rootTasks.first().childTasksTopDown().single { it.id == 1002 }

        val result = policy.apply(displayContent, defaultComponent, defaultOwner)

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = IsolatedTask(taskId = 1002, taskBounds = expectedFocusedTask.bounds),
                    contentTask =
                        TaskReference(
                            taskId = expectedFocusedTask.id,
                            component = expectedFocusedTask.componentName,
                            owner = UserHandle.of(expectedFocusedTask.userId),
                            bounds = expectedFocusedTask.bounds,
                        ),
                    owner = UserHandle.of(WORK),
                )
            )
    }

    @Test
    fun freeform_floatingWindows_withPrivate() = runTest {
        val displayContent =
            freeFormApps(
                TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                TaskSpec(taskId = 1003, name = YOUTUBE, userId = PRIVATE),
                TaskSpec(taskId = 1004, name = MESSAGES, userId = PERSONAL),
                focusedTaskId = 1004,
            )
        val expectedFocusedTask = displayContent.allTasks().single { it.id == 1004 }

        val result = policy.apply(displayContent, defaultComponent, defaultOwner)

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    contentTask =
                        TaskReference(
                            taskId = expectedFocusedTask.id,
                            component = expectedFocusedTask.componentName,
                            owner = UserHandle.of(expectedFocusedTask.userId),
                            bounds = expectedFocusedTask.bounds,
                        ),
                    owner = UserHandle.of(PRIVATE),
                )
            )
    }

    @Test
    fun freeform_floating_work() = runTest {
        val displayContent =
            freeFormApps(TaskSpec(taskId = 1002, name = FILES, userId = WORK), focusedTaskId = 1002)
        val expectedFocusedTask =
            displayContent.rootTasks.first().childTasksTopDown().single { it.id == 1002 }

        val result = policy.apply(displayContent, defaultComponent, defaultOwner)

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    contentTask =
                        TaskReference(
                            taskId = expectedFocusedTask.id,
                            component = expectedFocusedTask.componentName,
                            owner = UserHandle.of(expectedFocusedTask.userId),
                            bounds = expectedFocusedTask.bounds,
                        ),
                    owner = defaultOwner,
                )
            )
    }

    @Test
    fun fullScreen_shadeExpanded() = runTest {
        val displayContent =
            singleFullScreen(
                TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                shadeExpanded = true,
            )
        val expectedFocusedTask =
            displayContent.rootTasks.first().childTasksTopDown().single { it.id == 1002 }

        val result = policy.apply(displayContent, defaultComponent, defaultOwner)

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    contentTask =
                        TaskReference(
                            taskId = -1,
                            component = defaultComponent,
                            owner = defaultOwner,
                            bounds = Rect(),
                        ),
                    owner = defaultOwner,
                )
            )
    }

    @Test
    fun fullScreen_with_PictureInPicture() = runTest {
        val displayContent =
            pictureInPictureApp(
                pip = TaskSpec(taskId = 1002, name = YOUTUBE, userId = PERSONAL),
                fullScreen = TaskSpec(taskId = 1003, name = FILES, userId = WORK),
            )
        val expectedFocusedTask = displayContent.allTasks().single { it.id == 1003 }

        val result = policy.apply(displayContent, defaultComponent, defaultOwner)

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = IsolatedTask(taskId = 1003, taskBounds = FULL_SCREEN),
                    contentTask =
                        TaskReference(
                            taskId = expectedFocusedTask.id,
                            component = expectedFocusedTask.componentName,
                            owner = UserHandle.of(expectedFocusedTask.userId),
                            bounds = expectedFocusedTask.bounds,
                        ),
                    owner = UserHandle.of(WORK),
                )
            )
    }

    // TODO: PiP tasks should affect ownership (e.g. Private)
    @Test
    fun fullScreen_with_PictureInPicture_private() = runTest {
        val displayContent =
            pictureInPictureApp(
                pip = TaskSpec(taskId = 1002, name = YOUTUBE, userId = PRIVATE),
                fullScreen = TaskSpec(taskId = 1003, name = FILES, userId = PERSONAL),
            )
        val expectedFocusedTask = displayContent.allTasks().single { it.id == 1003 }

        val result = policy.apply(displayContent, defaultComponent, defaultOwner)
        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    contentTask =
                        TaskReference(
                            taskId = expectedFocusedTask.id,
                            component = expectedFocusedTask.componentName,
                            owner = UserHandle.of(expectedFocusedTask.userId),
                            bounds = expectedFocusedTask.bounds,
                        ),
                    owner = UserHandle.of(PRIVATE),
                )
            )
    }
}

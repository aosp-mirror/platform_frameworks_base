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
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.ActivityNames.FILES
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.ActivityNames.LAUNCHER
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
import com.android.systemui.screenshot.data.repository.profileTypeRepository
import com.android.systemui.screenshot.policy.CaptureType.FullScreen
import com.android.systemui.screenshot.policy.CaptureType.IsolatedTask
import com.android.systemui.screenshot.policy.CaptureType.RootTask
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

    @Test
    fun fullScreen_work() = runTest {
        val policy = ScreenshotPolicy(kosmos.profileTypeRepository)

        val result =
            policy.apply(
                singleFullScreen(TaskSpec(taskId = 1002, name = FILES, userId = WORK)),
                defaultComponent,
                defaultOwner,
            )

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = IsolatedTask(taskId = 1002, taskBounds = FULL_SCREEN),
                    component = ComponentName.unflattenFromString(FILES),
                    owner = UserHandle.of(WORK),
                )
            )
    }

    @Test
    fun fullScreen_private() = runTest {
        val policy = ScreenshotPolicy(kosmos.profileTypeRepository)

        val result =
            policy.apply(
                singleFullScreen(TaskSpec(taskId = 1002, name = YOUTUBE, userId = PRIVATE)),
                defaultComponent,
                defaultOwner,
            )

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    component = ComponentName.unflattenFromString(YOUTUBE),
                    owner = UserHandle.of(PRIVATE),
                )
            )
    }

    @Test
    fun splitScreen_workAndPersonal() = runTest {
        val policy = ScreenshotPolicy(kosmos.profileTypeRepository)

        val result =
            policy.apply(
                splitScreenApps(
                    first = TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                    second = TaskSpec(taskId = 1003, name = YOUTUBE, userId = PERSONAL),
                    focusedTaskId = 1002,
                ),
                defaultComponent,
                defaultOwner,
            )

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    component = ComponentName.unflattenFromString(YOUTUBE),
                    owner = UserHandle.of(PERSONAL),
                )
            )
    }

    @Test
    fun splitScreen_personalAndPrivate() = runTest {
        val policy = ScreenshotPolicy(kosmos.profileTypeRepository)

        val result =
            policy.apply(
                splitScreenApps(
                    first = TaskSpec(taskId = 1002, name = FILES, userId = PERSONAL),
                    second = TaskSpec(taskId = 1003, name = YOUTUBE, userId = PRIVATE),
                    focusedTaskId = 1002,
                ),
                defaultComponent,
                defaultOwner,
            )

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    component = ComponentName.unflattenFromString(YOUTUBE),
                    owner = UserHandle.of(PRIVATE),
                )
            )
    }

    @Test
    fun splitScreen_workAndPrivate() = runTest {
        val policy = ScreenshotPolicy(kosmos.profileTypeRepository)

        val result =
            policy.apply(
                splitScreenApps(
                    first = TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                    second = TaskSpec(taskId = 1003, name = YOUTUBE, userId = PRIVATE),
                    focusedTaskId = 1002,
                ),
                defaultComponent,
                defaultOwner,
            )

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    component = ComponentName.unflattenFromString(YOUTUBE),
                    owner = UserHandle.of(PRIVATE),
                )
            )
    }

    @Test
    fun splitScreen_twoWorkTasks() = runTest {
        val policy = ScreenshotPolicy(kosmos.profileTypeRepository)

        val result =
            policy.apply(
                splitScreenApps(
                    parentTaskId = 1,
                    parentBounds = FREEFORM_FULL_SCREEN,
                    orientation = VERTICAL,
                    first = TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                    second = TaskSpec(taskId = 1003, name = YOUTUBE, userId = WORK),
                    focusedTaskId = 1002,
                ),
                defaultComponent,
                defaultOwner,
            )

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type =
                        RootTask(
                            parentTaskId = 1,
                            taskBounds = FREEFORM_FULL_SCREEN,
                            childTaskIds = listOf(1002, 1003),
                        ),
                    component = ComponentName.unflattenFromString(FILES),
                    owner = UserHandle.of(WORK),
                )
            )
    }

    @Test
    fun freeform_floatingWindows() = runTest {
        val policy = ScreenshotPolicy(kosmos.profileTypeRepository)

        val result =
            policy.apply(
                freeFormApps(
                    TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                    TaskSpec(taskId = 1003, name = YOUTUBE, userId = PERSONAL),
                    focusedTaskId = 1003,
                ),
                defaultComponent,
                defaultOwner,
            )

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    component = ComponentName.unflattenFromString(YOUTUBE),
                    owner = UserHandle.of(PERSONAL),
                )
            )
    }

    @Test
    fun freeform_floatingWindows_maximized() = runTest {
        val policy = ScreenshotPolicy(kosmos.profileTypeRepository)

        val result =
            policy.apply(
                freeFormApps(
                    TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                    TaskSpec(taskId = 1003, name = YOUTUBE, userId = PERSONAL),
                    focusedTaskId = 1003,
                ),
                defaultComponent,
                defaultOwner,
            )

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    component = ComponentName.unflattenFromString(YOUTUBE),
                    owner = UserHandle.of(PERSONAL),
                )
            )
    }

    @Test
    fun freeform_floatingWindows_withPrivate() = runTest {
        val policy = ScreenshotPolicy(kosmos.profileTypeRepository)

        val result =
            policy.apply(
                freeFormApps(
                    TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                    TaskSpec(taskId = 1003, name = YOUTUBE, userId = PRIVATE),
                    TaskSpec(taskId = 1004, name = MESSAGES, userId = PERSONAL),
                    focusedTaskId = 1004,
                ),
                defaultComponent,
                defaultOwner,
            )

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    component = ComponentName.unflattenFromString(YOUTUBE),
                    owner = UserHandle.of(PRIVATE),
                )
            )
    }

    @Test
    fun freeform_floating_workOnly() = runTest {
        val policy = ScreenshotPolicy(kosmos.profileTypeRepository)

        val result =
            policy.apply(
                freeFormApps(
                    TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                    focusedTaskId = 1002,
                ),
                defaultComponent,
                defaultOwner,
            )

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    component = ComponentName.unflattenFromString(LAUNCHER),
                    owner = defaultOwner,
                )
            )
    }

    @Test
    fun fullScreen_shadeExpanded() = runTest {
        val policy = ScreenshotPolicy(kosmos.profileTypeRepository)

        val result =
            policy.apply(
                singleFullScreen(
                    TaskSpec(taskId = 1002, name = FILES, userId = WORK),
                    shadeExpanded = true,
                ),
                defaultComponent,
                defaultOwner,
            )

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = FullScreen(displayId = 0),
                    component = defaultComponent,
                    owner = defaultOwner,
                )
            )
    }

    @Test
    fun fullScreen_with_PictureInPicture() = runTest {
        val policy = ScreenshotPolicy(kosmos.profileTypeRepository)

        val result =
            policy.apply(
                pictureInPictureApp(
                    pip = TaskSpec(taskId = 1002, name = YOUTUBE, userId = PERSONAL),
                    fullScreen = TaskSpec(taskId = 1003, name = FILES, userId = WORK),
                ),
                defaultComponent,
                defaultOwner,
            )

        assertThat(result)
            .isEqualTo(
                CaptureParameters(
                    type = IsolatedTask(taskId = 1003, taskBounds = FULL_SCREEN),
                    component = ComponentName.unflattenFromString(FILES),
                    owner = UserHandle.of(WORK),
                )
            )
    }
}

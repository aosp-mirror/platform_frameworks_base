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
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.screenshot.data.model.DisplayContentModel
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.ActivityNames.FILES
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.ActivityNames.YOUTUBE
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.ActivityNames.YOUTUBE_PIP
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.RootTasks.emptyRootSplit
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.RootTasks.fullScreen
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.RootTasks.launcher
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.TaskSpec
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.pictureInPictureApp
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.singleFullScreen
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.splitScreenApps
import com.android.systemui.screenshot.data.model.SystemUiState
import com.android.systemui.screenshot.data.repository.profileTypeRepository
import com.android.systemui.screenshot.policy.CapturePolicy.PolicyResult.Matched
import com.android.systemui.screenshot.policy.CapturePolicy.PolicyResult.NotMatched
import com.android.systemui.screenshot.policy.CaptureType.FullScreen
import com.android.systemui.screenshot.policy.TestUserIds.PERSONAL
import com.android.systemui.screenshot.policy.TestUserIds.PRIVATE
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PrivateProfilePolicyTest {
    private val kosmos = Kosmos()
    private val policy = PrivateProfilePolicy(kosmos.profileTypeRepository)

    // TODO:
    // private app in PIP
    // private app below personal PIP app
    // Freeform windows

    @Test
    fun shadeExpanded_notMatched() = runTest {
        val result =
            policy.check(
                singleFullScreen(
                    spec = TaskSpec(taskId = 1002, name = YOUTUBE, userId = PRIVATE),
                    shadeExpanded = true
                )
            )

        assertThat(result)
            .isEqualTo(NotMatched(PrivateProfilePolicy.NAME, PrivateProfilePolicy.SHADE_EXPANDED))
    }

    @Test
    fun noPrivate_notMatched() = runTest {
        val result =
            policy.check(
                singleFullScreen(TaskSpec(taskId = 1002, name = YOUTUBE, userId = PERSONAL))
            )

        assertThat(result)
            .isEqualTo(NotMatched(PrivateProfilePolicy.NAME, PrivateProfilePolicy.NO_VISIBLE_TASKS))
    }

    @Test
    fun withPrivateFullScreen_isMatched() = runTest {
        val result =
            policy.check(
                singleFullScreen(TaskSpec(taskId = 1002, name = YOUTUBE, userId = PRIVATE))
            )

        assertThat(result)
            .isEqualTo(
                Matched(
                    PrivateProfilePolicy.NAME,
                    PrivateProfilePolicy.PRIVATE_TASK_VISIBLE,
                    CaptureParameters(
                        type = FullScreen(displayId = 0),
                        component = ComponentName.unflattenFromString(YOUTUBE),
                        owner = UserHandle.of(PRIVATE)
                    )
                )
            )
    }

    @Test
    fun withPrivateNotVisible_notMatched() = runTest {
        val result =
            policy.check(
                DisplayContentModel(
                    displayId = 0,
                    systemUiState = SystemUiState(shadeExpanded = false),
                    rootTasks =
                        listOf(
                            fullScreen(
                                TaskSpec(taskId = 1002, name = FILES, userId = PERSONAL),
                                visible = true
                            ),
                            fullScreen(
                                TaskSpec(taskId = 1003, name = YOUTUBE, userId = PRIVATE),
                                visible = false
                            ),
                            launcher(visible = false),
                            emptyRootSplit,
                        )
                )
            )

        assertThat(result)
            .isEqualTo(
                NotMatched(
                    PrivateProfilePolicy.NAME,
                    PrivateProfilePolicy.NO_VISIBLE_TASKS,
                )
            )
    }

    @Test
    fun withPrivateFocusedInSplitScreen_isMatched() = runTest {
        val result =
            policy.check(
                splitScreenApps(
                    top = TaskSpec(taskId = 1002, name = FILES, userId = PERSONAL),
                    bottom = TaskSpec(taskId = 1003, name = YOUTUBE, userId = PRIVATE),
                    focusedTaskId = 1003
                )
            )

        assertThat(result)
            .isEqualTo(
                Matched(
                    PrivateProfilePolicy.NAME,
                    PrivateProfilePolicy.PRIVATE_TASK_VISIBLE,
                    CaptureParameters(
                        type = FullScreen(displayId = 0),
                        component = ComponentName.unflattenFromString(YOUTUBE),
                        owner = UserHandle.of(PRIVATE)
                    )
                )
            )
    }

    @Test
    fun withPrivateNotFocusedInSplitScreen_isMatched() = runTest {
        val result =
            policy.check(
                splitScreenApps(
                    top = TaskSpec(taskId = 1002, name = FILES, userId = PERSONAL),
                    bottom = TaskSpec(taskId = 1003, name = YOUTUBE, userId = PRIVATE),
                    focusedTaskId = 1002
                )
            )

        assertThat(result)
            .isEqualTo(
                Matched(
                    PrivateProfilePolicy.NAME,
                    PrivateProfilePolicy.PRIVATE_TASK_VISIBLE,
                    CaptureParameters(
                        type = FullScreen(displayId = 0),
                        component = ComponentName.unflattenFromString(FILES),
                        owner = UserHandle.of(PRIVATE)
                    )
                )
            )
    }

    @Test
    fun withPrivatePictureInPictureApp_isMatched() = runTest {
        val result =
            policy.check(
                pictureInPictureApp(TaskSpec(taskId = 1002, name = YOUTUBE_PIP, userId = PRIVATE))
            )

        assertThat(result)
            .isEqualTo(
                Matched(
                    PrivateProfilePolicy.NAME,
                    PrivateProfilePolicy.PRIVATE_TASK_VISIBLE,
                    CaptureParameters(
                        type = FullScreen(displayId = 0),
                        component = ComponentName.unflattenFromString(YOUTUBE_PIP),
                        owner = UserHandle.of(PRIVATE)
                    )
                )
            )
    }

    @Test
    fun withPrivateAppBelowPictureInPictureApp_isMatched() = runTest {
        val result =
            policy.check(
                pictureInPictureApp(
                    pip = TaskSpec(taskId = 1002, name = YOUTUBE_PIP, userId = PERSONAL),
                    fullScreen = TaskSpec(taskId = 1003, name = FILES, userId = PRIVATE),
                )
            )

        assertThat(result)
            .isEqualTo(
                Matched(
                    PrivateProfilePolicy.NAME,
                    PrivateProfilePolicy.PRIVATE_TASK_VISIBLE,
                    CaptureParameters(
                        type = FullScreen(displayId = 0),
                        component = ComponentName.unflattenFromString(YOUTUBE_PIP),
                        owner = UserHandle.of(PRIVATE)
                    )
                )
            )
    }
}

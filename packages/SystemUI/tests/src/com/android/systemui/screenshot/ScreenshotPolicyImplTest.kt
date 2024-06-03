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

package com.android.systemui.screenshot

import android.app.ActivityTaskManager.RootTaskInfo
import android.app.IActivityTaskManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.UserHandle
import android.os.UserManager
import android.testing.AndroidTestingRunner
import com.android.systemui.SysuiTestCase
import com.android.systemui.screenshot.ScreenshotPolicy.DisplayContentInfo
import com.android.systemui.screenshot.policy.ActivityType.Home
import com.android.systemui.screenshot.policy.ActivityType.Undefined
import com.android.systemui.screenshot.policy.WindowingMode.FullScreen
import com.android.systemui.screenshot.policy.WindowingMode.PictureInPicture
import com.android.systemui.screenshot.policy.newChildTask
import com.android.systemui.screenshot.policy.newRootTaskInfo
import com.android.systemui.settings.FakeDisplayTracker
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

// The following values are chosen to be distinct from commonly seen real values
private const val DISPLAY_ID = 100
private const val PRIMARY_USER = 2000
private const val MANAGED_PROFILE_USER = 3000

@RunWith(AndroidTestingRunner::class)
class ScreenshotPolicyImplTest : SysuiTestCase() {

    @Test
    fun testToDisplayContentInfo() {
        assertThat(fullScreenWorkProfileTask.toDisplayContentInfo())
            .isEqualTo(
                DisplayContentInfo(
                    ComponentName(
                        "com.google.android.apps.nbu.files",
                        "com.google.android.apps.nbu.files.home.HomeActivity"
                    ),
                    Rect(0, 0, 1080, 2400),
                    UserHandle.of(MANAGED_PROFILE_USER),
                    65
                )
            )
    }

    @Test
    fun findPrimaryContent_ignoresPipTask() = runBlocking {
        val policy =
            fakeTasksPolicyImpl(
                mContext,
                shadeExpanded = false,
                tasks = listOf(pipTask, fullScreenWorkProfileTask, launcherTask, emptyTask)
            )

        val info = policy.findPrimaryContent(DISPLAY_ID)
        assertThat(info).isEqualTo(fullScreenWorkProfileTask.toDisplayContentInfo())
    }

    @Test
    fun findPrimaryContent_shadeExpanded_ignoresTopTask() = runBlocking {
        val policy =
            fakeTasksPolicyImpl(
                mContext,
                shadeExpanded = true,
                tasks = listOf(fullScreenWorkProfileTask, launcherTask, emptyTask)
            )

        val info = policy.findPrimaryContent(DISPLAY_ID)
        assertThat(info).isEqualTo(policy.systemUiContent)
    }

    @Test
    fun findPrimaryContent_emptyTaskList() = runBlocking {
        val policy = fakeTasksPolicyImpl(mContext, shadeExpanded = false, tasks = listOf())

        val info = policy.findPrimaryContent(DISPLAY_ID)
        assertThat(info).isEqualTo(policy.systemUiContent)
    }

    @Test
    fun findPrimaryContent_workProfileNotOnTop() = runBlocking {
        val policy =
            fakeTasksPolicyImpl(
                mContext,
                shadeExpanded = false,
                tasks = listOf(launcherTask, fullScreenWorkProfileTask, emptyTask)
            )

        val info = policy.findPrimaryContent(DISPLAY_ID)
        assertThat(info).isEqualTo(launcherTask.toDisplayContentInfo())
    }

    private fun fakeTasksPolicyImpl(
        context: Context,
        shadeExpanded: Boolean,
        tasks: List<RootTaskInfo>
    ): ScreenshotPolicyImpl {
        val userManager = mock<UserManager>()
        val atmService = mock<IActivityTaskManager>()
        val dispatcher = Dispatchers.Unconfined
        val displayTracker = FakeDisplayTracker(context)

        return object :
            ScreenshotPolicyImpl(context, userManager, atmService, dispatcher, displayTracker) {
            override suspend fun isManagedProfile(userId: Int) = (userId == MANAGED_PROFILE_USER)
            override suspend fun getAllRootTaskInfosOnDisplay(displayId: Int) = tasks
            override suspend fun isNotificationShadeExpanded() = shadeExpanded
        }
    }

    private val pipTask =
        newRootTaskInfo(
            taskId = 66,
            userId = PRIMARY_USER,
            displayId = DISPLAY_ID,
            bounds = Rect(628, 1885, 1038, 2295),
            windowingMode = PictureInPicture,
            topActivity = ComponentName.unflattenFromString(YOUTUBE_PIP_ACTIVITY),
        ) {
            listOf(newChildTask(taskId = 66, userId = 0, name = YOUTUBE_HOME_ACTIVITY))
        }

    private val fullScreenWorkProfileTask =
        newRootTaskInfo(
            taskId = 65,
            userId = MANAGED_PROFILE_USER,
            displayId = DISPLAY_ID,
            bounds = Rect(0, 0, 1080, 2400),
            windowingMode = FullScreen,
            topActivity = ComponentName.unflattenFromString(FILES_HOME_ACTIVITY),
        ) {
            listOf(
                newChildTask(taskId = 65, userId = MANAGED_PROFILE_USER, name = FILES_HOME_ACTIVITY)
            )
        }
    private val launcherTask =
        newRootTaskInfo(
            taskId = 1,
            userId = PRIMARY_USER,
            displayId = DISPLAY_ID,
            activityType = Home,
            windowingMode = FullScreen,
            bounds = Rect(0, 0, 1080, 2400),
            topActivity = ComponentName.unflattenFromString(LAUNCHER_ACTIVITY),
        ) {
            listOf(newChildTask(taskId = 1, userId = 0, name = LAUNCHER_ACTIVITY))
        }

    private val emptyTask =
        newRootTaskInfo(
            taskId = 2,
            userId = PRIMARY_USER,
            displayId = DISPLAY_ID,
            visible = false,
            running = false,
            numActivities = 0,
            activityType = Undefined,
            bounds = Rect(0, 0, 1080, 2400),
        ) {
            listOf(
                newChildTask(taskId = 3, name = ""),
                newChildTask(taskId = 4, name = ""),
            )
        }
}

private const val YOUTUBE_HOME_ACTIVITY =
    "com.google.android.youtube/" + "com.google.android.youtube.app.honeycomb.Shell\$HomeActivity"

private const val FILES_HOME_ACTIVITY =
    "com.google.android.apps.nbu.files/" + "com.google.android.apps.nbu.files.home.HomeActivity"

private const val YOUTUBE_PIP_ACTIVITY =
    "com.google.android.youtube/" +
        "com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity"

private const val LAUNCHER_ACTIVITY =
    "com.google.android.apps.nexuslauncher/" +
        "com.google.android.apps.nexuslauncher.NexusLauncherActivity"

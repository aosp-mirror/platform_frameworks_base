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
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_PINNED
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.UserHandle
import android.os.UserManager
import android.testing.AndroidTestingRunner
import com.android.systemui.SysuiTestCase
import com.android.systemui.screenshot.ScreenshotPolicy.DisplayContentInfo
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
                    65))
    }

    @Test
    fun findPrimaryContent_ignoresPipTask() = runBlocking {
        val policy = fakeTasksPolicyImpl(
            mContext,
            shadeExpanded = false,
            tasks = listOf(
                    pipTask,
                    fullScreenWorkProfileTask,
                    launcherTask,
                    emptyTask)
        )

        val info = policy.findPrimaryContent(DISPLAY_ID)
        assertThat(info).isEqualTo(fullScreenWorkProfileTask.toDisplayContentInfo())
    }

    @Test
    fun findPrimaryContent_shadeExpanded_ignoresTopTask() = runBlocking {
        val policy = fakeTasksPolicyImpl(
            mContext,
            shadeExpanded = true,
            tasks = listOf(
                fullScreenWorkProfileTask,
                launcherTask,
                emptyTask)
        )

        val info = policy.findPrimaryContent(DISPLAY_ID)
        assertThat(info).isEqualTo(policy.systemUiContent)
    }

    @Test
    fun findPrimaryContent_emptyTaskList() = runBlocking {
        val policy = fakeTasksPolicyImpl(
            mContext,
            shadeExpanded = false,
            tasks = listOf()
        )

        val info = policy.findPrimaryContent(DISPLAY_ID)
        assertThat(info).isEqualTo(policy.systemUiContent)
    }

    @Test
    fun findPrimaryContent_workProfileNotOnTop() = runBlocking {
        val policy = fakeTasksPolicyImpl(
            mContext,
            shadeExpanded = false,
            tasks = listOf(
                launcherTask,
                fullScreenWorkProfileTask,
                emptyTask)
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

        return object : ScreenshotPolicyImpl(context, userManager, atmService, dispatcher,
                displayTracker) {
            override suspend fun isManagedProfile(userId: Int) = (userId == MANAGED_PROFILE_USER)
            override suspend fun getAllRootTaskInfosOnDisplay(displayId: Int) = tasks
            override suspend fun isNotificationShadeExpanded() = shadeExpanded
        }
    }

    private val pipTask = RootTaskInfo().apply {
        configuration.windowConfiguration.apply {
            windowingMode = WINDOWING_MODE_PINNED
            setBounds(Rect(628, 1885, 1038, 2295))
            activityType = ACTIVITY_TYPE_STANDARD
        }
        displayId = DISPLAY_ID
        userId = PRIMARY_USER
        taskId = 66
        visible = true
        isVisible = true
        isRunning = true
        numActivities = 1
        topActivity = ComponentName(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity"
        )
        childTaskIds = intArrayOf(66)
        childTaskNames = arrayOf("com.google.android.youtube/" +
                "com.google.android.youtube.app.honeycomb.Shell\$HomeActivity")
        childTaskUserIds = intArrayOf(0)
        childTaskBounds = arrayOf(Rect(628, 1885, 1038, 2295))
    }

    private val fullScreenWorkProfileTask = RootTaskInfo().apply {
        configuration.windowConfiguration.apply {
            windowingMode = WINDOWING_MODE_FULLSCREEN
            setBounds(Rect(0, 0, 1080, 2400))
            activityType = ACTIVITY_TYPE_STANDARD
        }
        displayId = DISPLAY_ID
        userId = MANAGED_PROFILE_USER
        taskId = 65
        visible = true
        isVisible = true
        isRunning = true
        numActivities = 1
        topActivity = ComponentName(
            "com.google.android.apps.nbu.files",
            "com.google.android.apps.nbu.files.home.HomeActivity"
        )
        childTaskIds = intArrayOf(65)
        childTaskNames = arrayOf("com.google.android.apps.nbu.files/" +
                "com.google.android.apps.nbu.files.home.HomeActivity")
        childTaskUserIds = intArrayOf(MANAGED_PROFILE_USER)
        childTaskBounds = arrayOf(Rect(0, 0, 1080, 2400))
    }

    private val launcherTask = RootTaskInfo().apply {
        configuration.windowConfiguration.apply {
            windowingMode = WINDOWING_MODE_FULLSCREEN
            setBounds(Rect(0, 0, 1080, 2400))
            activityType = ACTIVITY_TYPE_HOME
        }
        displayId = DISPLAY_ID
        taskId = 1
        userId = PRIMARY_USER
        visible = true
        isVisible = true
        isRunning = true
        numActivities = 1
        topActivity = ComponentName(
            "com.google.android.apps.nexuslauncher",
            "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
        )
        childTaskIds = intArrayOf(1)
        childTaskNames = arrayOf("com.google.android.apps.nexuslauncher/" +
                "com.google.android.apps.nexuslauncher.NexusLauncherActivity")
        childTaskUserIds = intArrayOf(0)
        childTaskBounds = arrayOf(Rect(0, 0, 1080, 2400))
    }

    private val emptyTask = RootTaskInfo().apply {
        configuration.windowConfiguration.apply {
            windowingMode = WINDOWING_MODE_FULLSCREEN
            setBounds(Rect(0, 0, 1080, 2400))
            activityType = ACTIVITY_TYPE_UNDEFINED
        }
        displayId = DISPLAY_ID
        taskId = 2
        userId = PRIMARY_USER
        visible = false
        isVisible = false
        isRunning = false
        numActivities = 0
        childTaskIds = intArrayOf(3, 4)
        childTaskNames = arrayOf("", "")
        childTaskUserIds = intArrayOf(0, 0)
        childTaskBounds = arrayOf(Rect(0, 0, 1080, 2400), Rect(0, 2400, 1080, 4800))
    }
}

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
package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.SurfaceControl
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags
import com.android.wm.shell.MockToken
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.desktopmode.DesktopUserRepositories
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalSystemViewContainer
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

/**
 * Tests for [DesktopHeaderManageWindowsMenu].
 *
 * Build/Install/Run:
 * atest WMShellUnitTests:DesktopHeaderManageWindowsMenuTest
 */
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner::class)
class DesktopHeaderManageWindowsMenuTest : ShellTestCase() {

    @JvmField
    @Rule
    val setFlagsRule: SetFlagsRule = SetFlagsRule()

    private lateinit var userRepositories: DesktopUserRepositories
    private lateinit var menu: DesktopHeaderManageWindowsMenu

    @Before
    fun setUp() {
        userRepositories = DesktopUserRepositories(
            context = context,
            shellInit = ShellInit(TestShellExecutor()),
            persistentRepository = mock(),
            repositoryInitializer = mock(),
            mainCoroutineScope = mock(),
            userManager = mock(),
        )
    }

    @After
    fun tearDown() {
        menu.animateClose()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun testShow_forImmersiveTask_usesSystemViewContainer() {
        val task = createFreeformTask()
        userRepositories.getProfile(DEFAULT_USER_ID).setTaskInFullImmersiveState(
            displayId = task.displayId,
            taskId = task.taskId,
            immersive = true
        )
        menu = createMenu(task)

        assertThat(menu.menuViewContainer).isInstanceOf(AdditionalSystemViewContainer::class.java)
    }

    private fun createMenu(task: RunningTaskInfo) = DesktopHeaderManageWindowsMenu(
        callerTaskInfo = task,
        x = 0,
        y = 0,
        displayController = mock(),
        rootTdaOrganizer = mock(),
        context = context,
        desktopUserRepositories = userRepositories,
        surfaceControlBuilderSupplier = { SurfaceControl.Builder() },
        surfaceControlTransactionSupplier = { SurfaceControl.Transaction() },
        snapshotList = emptyList(),
        onIconClickListener = {},
        onOutsideClickListener = {},
    )

    private fun createFreeformTask(): RunningTaskInfo = TestRunningTaskInfoBuilder()
        .setToken(MockToken().token())
        .setActivityType(ACTIVITY_TYPE_STANDARD)
        .setWindowingMode(WINDOWING_MODE_FREEFORM)
        .setUserId(DEFAULT_USER_ID)
        .build()

    private companion object {
        const val DEFAULT_USER_ID = 10
    }
}

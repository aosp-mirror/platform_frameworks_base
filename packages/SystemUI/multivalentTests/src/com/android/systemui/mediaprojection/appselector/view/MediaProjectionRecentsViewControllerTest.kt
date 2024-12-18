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

package com.android.systemui.mediaprojection.appselector.view

import android.app.ActivityOptions
import android.app.IActivityTaskManager
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_PSS_APP_SELECTOR_RECENTS_SPLIT_SCREEN
import com.android.systemui.SysuiTestCase
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorResultHandler
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.util.mockito.mock
import com.android.wm.shell.shared.split.SplitBounds
import com.android.wm.shell.splitscreen.SplitScreen
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaProjectionRecentsViewControllerTest : SysuiTestCase() {

    @get:Rule val expect: Expect = Expect.create()

    private val recentTasksAdapter = mock<RecentTasksAdapter>()
    private val tasksAdapterFactory = RecentTasksAdapter.Factory { _, _ -> recentTasksAdapter }
    private val taskViewSizeProvider = mock<TaskPreviewSizeProvider>()
    private val activityTaskManager = mock<IActivityTaskManager>()
    private val resultHandler = mock<MediaProjectionAppSelectorResultHandler>()
    private val splitScreen = Optional.of(mock<SplitScreen>())
    private val bundleCaptor = ArgumentCaptor.forClass(Bundle::class.java)

    private val fullScreenTask =
        RecentTask(
            taskId = 123,
            displayId = 456,
            userId = 789,
            topActivityComponent = null,
            baseIntentComponent = null,
            colorBackground = null,
            isForegroundTask = false,
            userType = RecentTask.UserType.STANDARD,
            splitBounds = null
        )

    private val splitScreenTask =
        RecentTask(
            taskId = 123,
            displayId = 456,
            userId = 789,
            topActivityComponent = null,
            baseIntentComponent = null,
            colorBackground = null,
            isForegroundTask = false,
            userType = RecentTask.UserType.STANDARD,
            splitBounds = SplitBounds(Rect(), Rect(), 0, 0, 0)
        )

    private val taskView =
        View(context).apply {
            layoutParams = ViewGroup.LayoutParams(/* width = */ 100, /* height = */ 200)
        }

    private val controller =
        MediaProjectionRecentsViewController(
            tasksAdapterFactory,
            taskViewSizeProvider,
            activityTaskManager,
            resultHandler,
            splitScreen,
        )

    @Test
    fun onRecentAppClicked_fullScreenTaskWithSameIdIsStartedFromRecents() {
        controller.onRecentAppClicked(fullScreenTask, taskView)

        verify(activityTaskManager).startActivityFromRecents(eq(fullScreenTask.taskId), any())
    }

    @Test
    fun onRecentAppClicked_splitScreenTaskWithSameIdIsStartedFromRecents() {
        mSetFlagsRule.enableFlags(FLAG_PSS_APP_SELECTOR_RECENTS_SPLIT_SCREEN)
        controller.onRecentAppClicked(splitScreenTask, taskView)

        verify(splitScreen.get())
            .startTasks(
                eq(splitScreenTask.taskId),
                any(),
                anyInt(),
                any(),
                anyInt(),
                anyInt(),
                any(),
                any()
            )
    }

    @Test
    fun onRecentAppClicked_launchDisplayIdIsSet() {
        controller.onRecentAppClicked(fullScreenTask, taskView)

        assertThat(getStartedTaskActivityOptions(fullScreenTask.taskId).launchDisplayId)
            .isEqualTo(fullScreenTask.displayId)
    }

    @Test
    fun onRecentAppClicked_fullScreenTaskNotInForeground_usesScaleUpAnimation() {
        assertThat(fullScreenTask.isForegroundTask).isFalse()
        controller.onRecentAppClicked(fullScreenTask, taskView)

        assertThat(getStartedTaskActivityOptions(fullScreenTask.taskId).animationType)
            .isEqualTo(ActivityOptions.ANIM_SCALE_UP)
    }

    @Test
    fun onRecentAppClicked_fullScreenTaskInForeground_usesDefaultAnimation() {
        assertForegroundTaskUsesDefaultCloseAnimation(fullScreenTask)
    }

    @Test
    fun onRecentAppClicked_splitScreenTaskInForeground_flagOn_usesDefaultAnimation() {
        mSetFlagsRule.enableFlags(FLAG_PSS_APP_SELECTOR_RECENTS_SPLIT_SCREEN)
        assertForegroundTaskUsesDefaultCloseAnimation(splitScreenTask)
    }

    private fun assertForegroundTaskUsesDefaultCloseAnimation(task: RecentTask) {
        val foregroundTask = task.copy(isForegroundTask = true)
        controller.onRecentAppClicked(foregroundTask, taskView)

        expect
            .that(getStartedTaskActivityOptions(foregroundTask.taskId).animationType)
            .isEqualTo(ActivityOptions.ANIM_CUSTOM)
        expect
            .that(getStartedTaskActivityOptions(foregroundTask.taskId).overrideTaskTransition)
            .isTrue()
        expect
            .that(getStartedTaskActivityOptions(foregroundTask.taskId).customExitResId)
            .isEqualTo(com.android.internal.R.anim.resolver_close_anim)
        expect
            .that(getStartedTaskActivityOptions(foregroundTask.taskId).customEnterResId)
            .isEqualTo(0)
    }

    private fun getStartedTaskActivityOptions(taskId: Int): ActivityOptions {
        verify(activityTaskManager).startActivityFromRecents(eq(taskId), bundleCaptor.capture())
        return ActivityOptions.fromBundle(bundleCaptor.value)
    }
}

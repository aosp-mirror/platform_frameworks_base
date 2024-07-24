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
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_PSS_APP_SELECTOR_ABRUPT_EXIT_FIX
import com.android.systemui.SysuiTestCase
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorResultHandler
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.any
import org.mockito.Mockito.verify

@SmallTest
class MediaProjectionRecentsViewControllerTest : SysuiTestCase() {

    @get:Rule val expect: Expect = Expect.create()

    private val recentTasksAdapter = mock<RecentTasksAdapter>()
    private val tasksAdapterFactory = RecentTasksAdapter.Factory { _, _ -> recentTasksAdapter }
    private val taskViewSizeProvider = mock<TaskPreviewSizeProvider>()
    private val activityTaskManager = mock<IActivityTaskManager>()
    private val resultHandler = mock<MediaProjectionAppSelectorResultHandler>()
    private val bundleCaptor = ArgumentCaptor.forClass(Bundle::class.java)

    private val task =
        RecentTask(
            taskId = 123,
            displayId = 456,
            userId = 789,
            topActivityComponent = null,
            baseIntentComponent = null,
            colorBackground = null,
            isForegroundTask = false
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
            resultHandler
        )

    @Test
    fun onRecentAppClicked_taskWithSameIdIsStartedFromRecents() {
        controller.onRecentAppClicked(task, taskView)

        verify(activityTaskManager).startActivityFromRecents(eq(task.taskId), any())
    }

    @Test
    fun onRecentAppClicked_launchDisplayIdIsSet() {
        controller.onRecentAppClicked(task, taskView)

        assertThat(getStartedTaskActivityOptions().launchDisplayId).isEqualTo(task.displayId)
    }

    @Test
    fun onRecentAppClicked_taskNotInForeground_usesScaleUpAnimation() {
        controller.onRecentAppClicked(task, taskView)

        assertThat(getStartedTaskActivityOptions().animationType)
            .isEqualTo(ActivityOptions.ANIM_SCALE_UP)
    }

    @Test
    fun onRecentAppClicked_taskInForeground_flagOff_usesScaleUpAnimation() {
        mSetFlagsRule.disableFlags(FLAG_PSS_APP_SELECTOR_ABRUPT_EXIT_FIX)

        controller.onRecentAppClicked(task, taskView)

        assertThat(getStartedTaskActivityOptions().animationType)
            .isEqualTo(ActivityOptions.ANIM_SCALE_UP)
    }

    @Test
    fun onRecentAppClicked_taskInForeground_flagOn_usesDefaultAnimation() {
        mSetFlagsRule.enableFlags(FLAG_PSS_APP_SELECTOR_ABRUPT_EXIT_FIX)
        val foregroundTask = task.copy(isForegroundTask = true)

        controller.onRecentAppClicked(foregroundTask, taskView)

        expect
            .that(getStartedTaskActivityOptions().animationType)
            .isEqualTo(ActivityOptions.ANIM_CUSTOM)
        expect.that(getStartedTaskActivityOptions().overrideTaskTransition).isTrue()
        expect
            .that(getStartedTaskActivityOptions().customExitResId)
            .isEqualTo(com.android.internal.R.anim.resolver_close_anim)
        expect.that(getStartedTaskActivityOptions().customEnterResId).isEqualTo(0)
    }

    private fun getStartedTaskActivityOptions(): ActivityOptions {
        verify(activityTaskManager)
            .startActivityFromRecents(eq(task.taskId), bundleCaptor.capture())
        return ActivityOptions.fromBundle(bundleCaptor.value)
    }
}

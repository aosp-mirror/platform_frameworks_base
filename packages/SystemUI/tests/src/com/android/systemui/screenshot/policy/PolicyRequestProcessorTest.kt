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
import android.graphics.Bitmap
import android.graphics.Insets
import android.graphics.Rect
import android.os.UserHandle
import android.view.Display.DEFAULT_DISPLAY
import android.view.WindowManager.ScreenshotSource.SCREENSHOT_KEY_CHORD
import android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.systemui.screenshot.ImageCapture
import com.android.systemui.screenshot.ScreenshotData
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.ActivityNames.FILES
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.TaskSpec
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.launcherOnly
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.singleFullScreen
import com.android.systemui.screenshot.data.repository.DisplayContentRepository
import com.android.systemui.screenshot.policy.TestUserIds.PERSONAL
import com.android.systemui.screenshot.policy.TestUserIds.WORK
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PolicyRequestProcessorTest {
    /** Tests behavior when no policies are applied */
    @Test
    fun testProcess_defaultOwner_whenNoPolicyApplied() {
        val fullScreenWork = DisplayContentRepository {
            singleFullScreen(TaskSpec(taskId = TASK_ID, name = FILES, userId = WORK))
        }

        val request =
            ScreenshotData(
                TAKE_SCREENSHOT_FULLSCREEN,
                SCREENSHOT_KEY_CHORD,
                UserHandle.CURRENT,
                topComponent = null,
                originalScreenBounds = Rect(0, 0, 1, 1),
                taskId = -1,
                originalInsets = Insets.NONE,
                bitmap = null,
                displayId = DEFAULT_DISPLAY,
            )

        /* Create a policy request processor with no capture policies */
        val requestProcessor =
            PolicyRequestProcessor(
                Dispatchers.Unconfined,
                createImageCapture(),
                policies = emptyList(),
                defaultOwner = UserHandle.of(PERSONAL),
                defaultComponent = ComponentName("default", "Component"),
                displayTasks = fullScreenWork,
            )

        val result = runBlocking { requestProcessor.process(request) }

        assertWithMessage("With no policy, the screenshot should be assigned to the default user")
            .that(result.userHandle)
            .isEqualTo(UserHandle.of(PERSONAL))

        assertWithMessage("The topComponent of the screenshot")
            .that(result.topComponent)
            .isEqualTo(ComponentName.unflattenFromString(FILES))

        assertWithMessage("Task ID").that(result.taskId).isEqualTo(TASK_ID)
    }

    @Test
    fun testProcess_throwsWhenCaptureFails() {
        val request = ScreenshotData.forTesting()

        /* Create a policy request processor with no capture policies */
        val requestProcessor =
            PolicyRequestProcessor(
                Dispatchers.Unconfined,
                createImageCapture(display = null),
                policies = emptyList(),
                defaultComponent = ComponentName("default", "Component"),
                displayTasks = DisplayContentRepository { launcherOnly() },
            )

        val result = runCatching { runBlocking { requestProcessor.process(request) } }

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun testProcess_throwsWhenTaskCaptureFails() {
        val request = ScreenshotData.forTesting()
        val fullScreenWork = DisplayContentRepository {
            singleFullScreen(TaskSpec(taskId = TASK_ID, name = FILES, userId = WORK))
        }

        val captureTaskPolicy = CapturePolicy {
            CapturePolicy.PolicyResult.Matched(
                policy = "",
                reason = "",
                parameters =
                    CaptureParameters(
                        CaptureType.IsolatedTask(taskId = 0, taskBounds = null),
                        null,
                        UserHandle.CURRENT,
                    ),
            )
        }

        /* Create a policy request processor with no capture policies */
        val requestProcessor =
            PolicyRequestProcessor(
                Dispatchers.Unconfined,
                createImageCapture(task = null),
                policies = listOf(captureTaskPolicy),
                defaultComponent = ComponentName("default", "Component"),
                displayTasks = fullScreenWork,
            )

        val result = runCatching { runBlocking { requestProcessor.process(request) } }

        assertThat(result.isFailure).isTrue()
    }

    private fun createImageCapture(
        display: Bitmap? = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
        task: Bitmap? = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
    ) =
        object : ImageCapture {
            override fun captureDisplay(displayId: Int, crop: Rect?) = display

            override suspend fun captureTask(taskId: Int) = task
        }

    companion object {
        const val TASK_ID = 1001
    }
}

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
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.singleFullScreen
import com.android.systemui.screenshot.data.repository.DisplayContentRepository
import com.android.systemui.screenshot.policy.TestUserIds.PERSONAL
import com.android.systemui.screenshot.policy.TestUserIds.WORK
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PolicyRequestProcessorTest {

    val imageCapture =
        object : ImageCapture {
            override fun captureDisplay(displayId: Int, crop: Rect?) = null

            override suspend fun captureTask(taskId: Int) = null
        }

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
                null,
                topComponent = null,
                screenBounds = Rect(0, 0, 1, 1),
                taskId = -1,
                insets = Insets.NONE,
                bitmap = null,
                displayId = DEFAULT_DISPLAY
            )

        /* Create a policy request processor with no capture policies */
        val requestProcessor =
            PolicyRequestProcessor(
                Dispatchers.Unconfined,
                imageCapture,
                policies = emptyList(),
                defaultOwner = UserHandle.of(PERSONAL),
                defaultComponent = ComponentName("default", "Component"),
                displayTasks = fullScreenWork
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

    companion object {
        const val TASK_ID = 1001
    }
}

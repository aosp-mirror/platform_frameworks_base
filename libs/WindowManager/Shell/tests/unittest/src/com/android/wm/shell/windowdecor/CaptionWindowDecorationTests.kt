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

import android.app.ActivityManager
import android.app.WindowConfiguration
import android.content.ComponentName
import android.testing.AndroidTestingRunner
import android.view.Display
import android.view.InsetsState
import android.view.WindowInsetsController
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class CaptionWindowDecorationTests : ShellTestCase() {
    @Test
    fun updateRelayoutParams_freeformAndTransparentAppearance_allowsInputFallthrough() {
        val taskInfo = createTaskInfo()
        taskInfo.configuration.windowConfiguration.windowingMode =
            WindowConfiguration.WINDOWING_MODE_FREEFORM
        taskInfo.taskDescription!!.topOpaqueSystemBarsAppearance =
            WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
        val relayoutParams = WindowDecoration.RelayoutParams()

        CaptionWindowDecoration.updateRelayoutParams(
            relayoutParams,
            taskInfo,
            true,
            false,
            true /* isStatusBarVisible */,
            false /* isKeyguardVisibleAndOccluded */,
            InsetsState(),
            true /* hasGlobalFocus */
        )

        Truth.assertThat(relayoutParams.hasInputFeatureSpy()).isTrue()
    }

    @Test
    fun updateRelayoutParams_freeformButOpaqueAppearance_disallowsInputFallthrough() {
        val taskInfo = createTaskInfo()
        taskInfo.configuration.windowConfiguration.windowingMode =
            WindowConfiguration.WINDOWING_MODE_FREEFORM
        taskInfo.taskDescription!!.topOpaqueSystemBarsAppearance = 0
        val relayoutParams = WindowDecoration.RelayoutParams()

        CaptionWindowDecoration.updateRelayoutParams(
            relayoutParams,
            taskInfo,
            true,
            false,
            true /* isStatusBarVisible */,
            false /* isKeyguardVisibleAndOccluded */,
            InsetsState(),
            true /* hasGlobalFocus */
        )

        Truth.assertThat(relayoutParams.hasInputFeatureSpy()).isFalse()
    }

    @Test
    fun updateRelayoutParams_addOccludingCaptionElementCorrectly() {
        val taskInfo = createTaskInfo()
        val relayoutParams = WindowDecoration.RelayoutParams()
        CaptionWindowDecoration.updateRelayoutParams(
            relayoutParams,
            taskInfo,
            true,
            false,
            true /* isStatusBarVisible */,
            false /* isKeyguardVisibleAndOccluded */,
            InsetsState(),
            true /* hasGlobalFocus */
        )
        Truth.assertThat(relayoutParams.mOccludingCaptionElements.size).isEqualTo(2)
        Truth.assertThat(relayoutParams.mOccludingCaptionElements[0].mAlignment).isEqualTo(
            WindowDecoration.RelayoutParams.OccludingCaptionElement.Alignment.START)
        Truth.assertThat(relayoutParams.mOccludingCaptionElements[1].mAlignment).isEqualTo(
            WindowDecoration.RelayoutParams.OccludingCaptionElement.Alignment.END)
    }

    private fun createTaskInfo(): ActivityManager.RunningTaskInfo {
        val taskDescriptionBuilder =
            ActivityManager.TaskDescription.Builder()
        val taskInfo = TestRunningTaskInfoBuilder()
            .setDisplayId(Display.DEFAULT_DISPLAY)
            .setTaskDescriptionBuilder(taskDescriptionBuilder)
            .setVisible(true)
            .build()
        taskInfo.realActivity = ComponentName(
            "com.android.wm.shell.windowdecor",
            "CaptionWindowDecorationTests"
        )
        taskInfo.baseActivity = ComponentName(
            "com.android.wm.shell.windowdecor",
            "CaptionWindowDecorationTests"
        )
        return taskInfo
    }
}

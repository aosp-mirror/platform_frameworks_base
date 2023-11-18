/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.spa.screenshot.widget.preference

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.screenshot.util.settingsScreenshotTestRule
import com.android.settingslib.spa.widget.preference.ProgressBarPreference
import com.android.settingslib.spa.widget.preference.ProgressBarPreferenceModel
import com.android.settingslib.spa.widget.preference.ProgressBarWithDataPreference
import com.android.settingslib.spa.widget.ui.CircularProgressBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters
import platform.test.screenshot.DeviceEmulationSpec
import platform.test.screenshot.PhoneAndTabletMinimal

/** A screenshot test for ExampleFeature. */
@RunWith(ParameterizedAndroidJunit4::class)
class ProgressBarPreferenceScreenshotTest(emulationSpec: DeviceEmulationSpec) {
    companion object {
        @Parameters(name = "{0}")
        @JvmStatic
        fun getTestSpecs() = DeviceEmulationSpec.PhoneAndTabletMinimal
    }

    @get:Rule
    val screenshotRule =
        settingsScreenshotTestRule(
            emulationSpec,
        )

    @Test
    fun test() {
        screenshotRule.screenshotTest("progressBar") {
            Column {
                LargeProgressBar()
                SimpleProgressBar()
                ProgressBarWithData()
                CircularProgressBar(progress = 0.8f, radius = 160f)
            }
        }
    }
}

@Composable
private fun LargeProgressBar() {
    ProgressBarPreference(object : ProgressBarPreferenceModel {
        override val title = "Large Progress Bar"
        override val progress = 0.2f
        override val height = 20f
    })
}

@Composable
private fun SimpleProgressBar() {
    ProgressBarPreference(object : ProgressBarPreferenceModel {
        override val title = "Simple Progress Bar"
        override val progress = 0.2f
        override val icon = Icons.Outlined.SystemUpdate
    })
}

@Composable
private fun ProgressBarWithData() {
    ProgressBarWithDataPreference(model = object : ProgressBarPreferenceModel {
        override val title = "Progress Bar with Data"
        override val progress = 0.2f
        override val icon = Icons.Outlined.Delete
    }, data = "25G")
}

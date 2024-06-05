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
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.DisabledByDefault
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.screenshot.util.settingsScreenshotTestRule
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.ui.SettingsIcon
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters
import platform.test.screenshot.DeviceEmulationSpec
import platform.test.screenshot.PhoneAndTabletMinimal

/** A screenshot test for ExampleFeature. */
@RunWith(ParameterizedAndroidJunit4::class)
class PreferenceScreenshotTest(emulationSpec: DeviceEmulationSpec) {
    companion object {
        @Parameters(name = "{0}")
        @JvmStatic
        fun getTestSpecs() = DeviceEmulationSpec.PhoneAndTabletMinimal
        private const val TITLE = "Title"
        private const val SUMMARY = "Summary"
        private const val LONG_SUMMARY =
            "Long long long long long long long long long long long long long long long summary"
    }

    @get:Rule
    val screenshotRule =
        settingsScreenshotTestRule(
            emulationSpec,
        )

    @Test
    fun test() {
        screenshotRule.screenshotTest("preference") {
            Column {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                })

                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val summary = { SUMMARY }
                })

                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val summary = { LONG_SUMMARY }
                })

                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val summary = { SUMMARY }
                    override val enabled = { false }
                    override val icon = @Composable {
                        SettingsIcon(imageVector = Icons.Outlined.DisabledByDefault)
                    }
                })

                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val summary = { SUMMARY }
                    override val icon = @Composable {
                        SettingsIcon(imageVector = Icons.Outlined.Autorenew)
                    }
                })
            }
        }
    }
}

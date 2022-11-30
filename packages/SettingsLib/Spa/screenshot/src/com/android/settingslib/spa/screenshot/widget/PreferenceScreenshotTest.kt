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

package com.android.settingslib.spa.screenshot

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.DisabledByDefault
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.framework.compose.toState
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.SettingsIcon
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import platform.test.screenshot.DeviceEmulationSpec

/** A screenshot test for ExampleFeature. */
@RunWith(Parameterized::class)
class PreferenceScreenshotTest(emulationSpec: DeviceEmulationSpec) {
    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getTestSpecs() = DeviceEmulationSpec.PhoneAndTabletFull
        private const val TITLE = "Title"
        private const val SUMMARY = "Summary"
        private const val LONG_SUMMARY =
            "Long long long long long long long long long long long long long long long summary"
    }

    @get:Rule
    val screenshotRule =
        SettingsScreenshotTestRule(
            emulationSpec,
            "frameworks/base/packages/SettingsLib/Spa/screenshot/assets"
        )

    @Test
    fun testPreference() {
        screenshotRule.screenshotTest("preference") {
            RegularScaffold(title = "Preference") {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                })

                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val summary = SUMMARY.toState()
                })

                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val summary = LONG_SUMMARY.toState()
                })

                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val summary = SUMMARY.toState()
                    override val enabled = false.toState()
                    override val icon = @Composable {
                        SettingsIcon(imageVector = Icons.Outlined.DisabledByDefault)
                    }
                })

                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val summary = SUMMARY.toState()
                    override val icon = @Composable {
                        SettingsIcon(imageVector = Icons.Outlined.Autorenew)
                    }
                })
            }
        }
    }
}
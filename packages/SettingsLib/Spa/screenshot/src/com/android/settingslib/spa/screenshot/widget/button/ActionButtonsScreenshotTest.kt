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

package com.android.settingslib.spa.screenshot.widget.button

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.WarningAmber
import com.android.settingslib.spa.screenshot.util.SettingsScreenshotTestRule
import com.android.settingslib.spa.widget.button.ActionButton
import com.android.settingslib.spa.widget.button.ActionButtons
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters
import platform.test.screenshot.DeviceEmulationSpec
import platform.test.screenshot.PhoneAndTabletMinimal

/** A screenshot test for ExampleFeature. */
@RunWith(ParameterizedAndroidJunit4::class)
class ActionButtonsScreenshotTest(emulationSpec: DeviceEmulationSpec) {
    companion object {
        @Parameters(name = "{0}")
        @JvmStatic
        fun getTestSpecs() = DeviceEmulationSpec.PhoneAndTabletMinimal
    }

    @get:Rule
    val screenshotRule =
        SettingsScreenshotTestRule(
            emulationSpec,
            "frameworks/base/packages/SettingsLib/Spa/screenshot/assets"
        )

    @Test
    fun test() {
        screenshotRule.screenshotTest("actionButtons") {
            val actionButtons = listOf(
                ActionButton(text = "Open", imageVector = Icons.AutoMirrored.Outlined.Launch) {},
                ActionButton(text = "Uninstall", imageVector = Icons.Outlined.Delete) {},
                ActionButton(text = "Force stop", imageVector = Icons.Outlined.WarningAmber) {},
            )
            ActionButtons(actionButtons)
        }
    }
}

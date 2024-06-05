/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.input.screenshot

import android.content.Context
import android.hardware.input.KeyboardLayout
import android.os.LocaleList
import android.platform.test.flag.junit.SetFlagsRule
import com.android.hardware.input.Flags
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters
import platform.test.screenshot.DeviceEmulationSpec

/** A screenshot test for Keyboard layout preview for Iso physical layout. */
@RunWith(ParameterizedAndroidJunit4::class)
class KeyboardLayoutPreviewIsoScreenshotTest(emulationSpec: DeviceEmulationSpec) {
    companion object {
        @Parameters(name = "{0}")
        @JvmStatic
        fun getTestSpecs() = DeviceEmulationSpec.PhoneAndTabletMinimal
    }

    val setFlagsRule = SetFlagsRule()
    val screenshotRule = InputScreenshotTestRule(
            emulationSpec,
            "frameworks/base/tests/InputScreenshotTest/assets"
    )

    @get:Rule
    val ruleChain = RuleChain.outerRule(screenshotRule).around(setFlagsRule)

    @Test
    fun test() {
        setFlagsRule.enableFlags(Flags.FLAG_KEYBOARD_LAYOUT_PREVIEW_FLAG)
        screenshotRule.screenshotTest("layout-preview") {
            context: Context -> LayoutPreview.createLayoutPreview(context, null)
        }
    }

}

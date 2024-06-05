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
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import platform.test.screenshot.DeviceEmulationRule
import platform.test.screenshot.DeviceEmulationSpec
import platform.test.screenshot.MaterialYouColorsRule
import platform.test.screenshot.ScreenshotTestRule
import platform.test.screenshot.getEmulatedDevicePathConfig

/** A rule for Input screenshot diff tests. */
class InputScreenshotTestRule(
        emulationSpec: DeviceEmulationSpec,
        assetsPathRelativeToBuildRoot: String
) : TestRule {
    private val colorsRule = MaterialYouColorsRule()
    private val deviceEmulationRule = DeviceEmulationRule(emulationSpec)
    private val screenshotRule =
        ScreenshotTestRule(
            InputGoldenPathManager(
                getEmulatedDevicePathConfig(emulationSpec),
                assetsPathRelativeToBuildRoot
            )
        )
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val roboRule =
            RuleChain.outerRule(deviceEmulationRule)
                .around(screenshotRule)
                .around(composeRule)
    private val delegateRule = RuleChain.outerRule(colorsRule).around(roboRule)
    private val matcher = UnitTestBitmapMatcher
    private val isRobolectric = if (Build.FINGERPRINT.contains("robolectric")) true else false

    override fun apply(base: Statement, description: Description): Statement {
        val ruleToApply = if (isRobolectric) roboRule else delegateRule
        return ruleToApply.apply(base, description)
    }

    /**
     * Compare [content] with the golden image identified by [goldenIdentifier].
     */
    fun screenshotTest(
            goldenIdentifier: String,
            content: (Context) -> Bitmap,
    ) {
        // Make sure that the activity draws full screen and fits the whole display.
        val activity = composeRule.activity
        activity.mainExecutor.execute { activity.window.setDecorFitsSystemWindows(false) }

        // Set the content using the AndroidComposeRule to make sure that the Activity is set up
        // correctly.
        composeRule.setContent {
            Image(
                bitmap = content(activity).asImageBitmap(),
                contentDescription = null,
            )
        }
        composeRule.waitForIdle()

        val view = (composeRule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        screenshotRule.assertBitmapAgainstGolden(view.drawIntoBitmap(), goldenIdentifier, matcher)
    }
}

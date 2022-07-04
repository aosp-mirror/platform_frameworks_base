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

package com.android.systemui.testing.screenshot

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Assert.assertEquals
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import platform.test.screenshot.DeviceEmulationRule
import platform.test.screenshot.DeviceEmulationSpec
import platform.test.screenshot.MaterialYouColorsRule
import platform.test.screenshot.ScreenshotTestRule
import platform.test.screenshot.getEmulatedDevicePathConfig

/** A rule for View screenshot diff unit tests. */
class ViewScreenshotTestRule(emulationSpec: DeviceEmulationSpec) : TestRule {
    private val colorsRule = MaterialYouColorsRule()
    private val deviceEmulationRule = DeviceEmulationRule(emulationSpec)
    private val screenshotRule =
        ScreenshotTestRule(
            SystemUIGoldenImagePathManager(getEmulatedDevicePathConfig(emulationSpec))
        )
    private val activityRule = ActivityScenarioRule(ScreenshotActivity::class.java)
    private val delegateRule =
        RuleChain.outerRule(colorsRule)
            .around(deviceEmulationRule)
            .around(screenshotRule)
            .around(activityRule)
    private val matcher = UnitTestBitmapMatcher

    override fun apply(base: Statement, description: Description): Statement {
        return delegateRule.apply(base, description)
    }

    /**
     * Compare the content of the view provided by [viewProvider] with the golden image identified
     * by [goldenIdentifier] in the context of [emulationSpec].
     */
    fun screenshotTest(
        goldenIdentifier: String,
        layoutParams: LayoutParams =
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        viewProvider: (Activity) -> View,
    ) {
        activityRule.scenario.onActivity { activity ->
            // Make sure that the activity draws full screen and fits the whole display instead of
            // the system bars.
            activity.window.setDecorFitsSystemWindows(false)
            activity.setContentView(viewProvider(activity), layoutParams)
        }

        // We call onActivity again because it will make sure that our Activity is done measuring,
        // laying out and drawing its content (that we set in the previous onActivity lambda).
        activityRule.scenario.onActivity { activity ->
            // Check that the content is what we expected.
            val content = activity.requireViewById<ViewGroup>(android.R.id.content)
            assertEquals(1, content.childCount)
            screenshotRule.assertBitmapAgainstGolden(
                content.getChildAt(0).drawIntoBitmap(),
                goldenIdentifier,
                matcher
            )
        }
    }

    /**
     * Compare the content of the dialog provided by [dialogProvider] with the golden image
     * identified by [goldenIdentifier] in the context of [emulationSpec].
     */
    fun dialogScreenshotTest(
        goldenIdentifier: String,
        dialogProvider: (Activity) -> Dialog,
    ) {
        var dialog: Dialog? = null
        activityRule.scenario.onActivity { activity ->
            dialog =
                dialogProvider(activity).apply {
                    // Make sure that the dialog draws full screen and fits the whole display
                    // instead of the system bars.
                    window.setDecorFitsSystemWindows(false)

                    // Disable enter/exit animations.
                    create()
                    window.setWindowAnimations(0)

                    // Show the dialog.
                    show()
                }
        }

        // We call onActivity again because it will make sure that our Dialog is done measuring,
        // laying out and drawing its content (that we set in the previous onActivity lambda).
        activityRule.scenario.onActivity {
            // Check that the content is what we expected.
            val dialog = dialog ?: error("dialog is null")
            try {
                screenshotRule.assertBitmapAgainstGolden(
                    dialog.window.decorView.drawIntoBitmap(),
                    goldenIdentifier,
                    matcher,
                )
            } finally {
                dialog.dismiss()
            }
        }
    }
}

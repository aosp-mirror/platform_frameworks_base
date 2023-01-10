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
import android.graphics.Color
import android.view.View
import android.view.Window
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import platform.test.screenshot.*

/**
 * A rule that allows to run a screenshot diff test on a view that is hosted in another activity.
 */
class ExternalViewScreenshotTestRule(
    emulationSpec: DeviceEmulationSpec,
    assetPathRelativeToBuildRoot: String
) : TestRule {

    private val colorsRule = MaterialYouColorsRule()
    private val deviceEmulationRule = DeviceEmulationRule(emulationSpec)
    private val screenshotRule =
        ScreenshotTestRule(
            SystemUIGoldenImagePathManager(
                getEmulatedDevicePathConfig(emulationSpec),
                assetPathRelativeToBuildRoot
            )
        )
    private val delegateRule =
        RuleChain.outerRule(colorsRule).around(deviceEmulationRule).around(screenshotRule)
    private val matcher = UnitTestBitmapMatcher

    override fun apply(base: Statement, description: Description): Statement {
        return delegateRule.apply(base, description)
    }

    /**
     * Compare the content of the [view] with the golden image identified by [goldenIdentifier] in
     * the context of [emulationSpec]. Window must be specified to capture views that render
     * hardware buffers.
     */
    fun screenshotTest(goldenIdentifier: String, view: View, window: Window? = null) {
        view.removeElevationRecursively()

        ScreenshotRuleAsserter.Builder(screenshotRule)
            .setScreenshotProvider { view.toBitmap(window) }
            .withMatcher(matcher)
            .build()
            .assertGoldenImage(goldenIdentifier)
    }

    /**
     * Compare the content of the [activity] with the golden image identified by [goldenIdentifier]
     * in the context of [emulationSpec].
     */
    fun activityScreenshotTest(
        goldenIdentifier: String,
        activity: Activity,
    ) {
        val rootView = activity.window.decorView

        // Hide system bars, remove insets, focus and make sure device-specific cutouts
        // don't affect screenshots
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val window = activity.window
            window.setDecorFitsSystemWindows(false)
            WindowInsetsControllerCompat(window, rootView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.attributes =
                window.attributes.apply {
                    layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }

            rootView.removeInsetsRecursively()
            activity.currentFocus?.clearFocus()
        }

        screenshotTest(goldenIdentifier, rootView, activity.window)
    }
}

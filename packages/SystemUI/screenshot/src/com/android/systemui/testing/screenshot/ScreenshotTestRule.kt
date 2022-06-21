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

import android.app.UiModeManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.UserHandle
import android.view.Display
import android.view.View
import android.view.WindowManagerGlobal
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import platform.test.screenshot.GoldenImagePathManager
import platform.test.screenshot.PathConfig
import platform.test.screenshot.PathElementNoContext
import platform.test.screenshot.ScreenshotTestRule
import platform.test.screenshot.matchers.PixelPerfectMatcher

/**
 * A base rule for screenshot diff tests.
 *
 * This rules takes care of setting up the activity according to [testSpec] by:
 * - emulating the display size and density.
 * - setting the dark/light mode.
 * - setting the system (Material You) colors to a fixed value.
 *
 * @see ComposeScreenshotTestRule
 * @see ViewScreenshotTestRule
 */
class ScreenshotTestRule(private val testSpec: ScreenshotTestSpec) : TestRule {
    private var currentDisplay: DisplaySpec? = null
    private var currentGoldenIdentifier: String? = null

    private val pathConfig =
        PathConfig(
            PathElementNoContext("model", isDir = true) {
                currentDisplay?.name ?: error("currentDisplay is null")
            },
        )
    private val defaultMatcher = PixelPerfectMatcher()

    private val screenshotRule =
        ScreenshotTestRule(
            SystemUIGoldenImagePathManager(
                pathConfig,
                currentGoldenIdentifier = {
                    currentGoldenIdentifier ?: error("currentGoldenIdentifier is null")
                },
            )
        )

    override fun apply(base: Statement, description: Description): Statement {
        // The statement which call beforeTest() before running the test and afterTest() afterwards.
        val statement =
            object : Statement() {
                override fun evaluate() {
                    try {
                        beforeTest()
                        base.evaluate()
                    } finally {
                        afterTest()
                    }
                }
            }

        return screenshotRule.apply(statement, description)
    }

    private fun beforeTest() {
        // Update the system colors to a fixed color, so that tests don't depend on the host device
        // extracted colors. Note that we don't restore the default device colors at the end of the
        // test because changing the colors (and waiting for them to be applied) is costly and makes
        // the screenshot tests noticeably slower.
        DynamicColorsTestUtils.updateSystemColorsToOrange()

        // Emulate the display size and density.
        val display = testSpec.display
        val density = display.densityDpi
        val wm = WindowManagerGlobal.getWindowManagerService()
        val (width, height) = getEmulatedDisplaySize()
        wm.setForcedDisplayDensityForUser(Display.DEFAULT_DISPLAY, density, UserHandle.myUserId())
        wm.setForcedDisplaySize(Display.DEFAULT_DISPLAY, width, height)

        // Force the dark/light theme.
        val uiModeManager =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        uiModeManager.setApplicationNightMode(
            if (testSpec.isDarkTheme) {
                UiModeManager.MODE_NIGHT_YES
            } else {
                UiModeManager.MODE_NIGHT_NO
            }
        )
    }

    private fun afterTest() {
        // Reset the density and display size.
        val wm = WindowManagerGlobal.getWindowManagerService()
        wm.clearForcedDisplayDensityForUser(Display.DEFAULT_DISPLAY, UserHandle.myUserId())
        wm.clearForcedDisplaySize(Display.DEFAULT_DISPLAY)

        // Reset the dark/light theme.
        val uiModeManager =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
    }

    /**
     * Compare the content of [view] with the golden image identified by [goldenIdentifier] in the
     * context of [testSpec].
     */
    fun screenshotTest(goldenIdentifier: String, view: View) {
        val bitmap = drawIntoBitmap(view)

        // Compare bitmap against golden asset.
        val isDarkTheme = testSpec.isDarkTheme
        val isLandscape = testSpec.isLandscape
        val identifierWithSpec = buildString {
            append(goldenIdentifier)
            if (isDarkTheme) append("_dark")
            if (isLandscape) append("_landscape")
        }

        // TODO(b/230832101): Provide a way to pass a PathConfig and override the file name on
        // device to assertBitmapAgainstGolden instead?
        currentDisplay = testSpec.display
        currentGoldenIdentifier = goldenIdentifier
        screenshotRule.assertBitmapAgainstGolden(bitmap, identifierWithSpec, defaultMatcher)
        currentDisplay = null
        currentGoldenIdentifier = goldenIdentifier
    }

    /** Draw [view] into a [Bitmap]. */
    private fun drawIntoBitmap(view: View): Bitmap {
        val bitmap =
            Bitmap.createBitmap(
                view.measuredWidth,
                view.measuredHeight,
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    /** Get the emulated display size for [testSpec]. */
    private fun getEmulatedDisplaySize(): Pair<Int, Int> {
        val display = testSpec.display
        val isPortraitNaturalPosition = display.width < display.height
        return if (testSpec.isLandscape) {
            if (isPortraitNaturalPosition) {
                display.height to display.width
            } else {
                display.width to display.height
            }
        } else {
            if (isPortraitNaturalPosition) {
                display.width to display.height
            } else {
                display.height to display.width
            }
        }
    }
}

private class SystemUIGoldenImagePathManager(
    pathConfig: PathConfig,
    private val currentGoldenIdentifier: () -> String,
) :
    GoldenImagePathManager(
        appContext = InstrumentationRegistry.getInstrumentation().context,
        deviceLocalPath =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .filesDir
                .absolutePath
                .toString() + "/sysui_screenshots",
        pathConfig = pathConfig,
    ) {
    // This string is appended to all actual/expected screenshots on the device. We append the
    // golden identifier so that our pull_golden.py scripts can map a screenshot on device to its
    // asset (and automatically update it, if necessary).
    override fun toString() = currentGoldenIdentifier()
}

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
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import java.util.concurrent.TimeUnit
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
import platform.test.screenshot.matchers.BitmapMatcher

/** A rule for View screenshot diff unit tests. */
open class ViewScreenshotTestRule(
    emulationSpec: DeviceEmulationSpec,
    private val matcher: BitmapMatcher = UnitTestBitmapMatcher,
    assetsPathRelativeToBuildRoot: String
) : TestRule {
    private val colorsRule = MaterialYouColorsRule()
    private val deviceEmulationRule = DeviceEmulationRule(emulationSpec)
    protected val screenshotRule =
        ScreenshotTestRule(
            SystemUIGoldenImagePathManager(
                getEmulatedDevicePathConfig(emulationSpec),
                assetsPathRelativeToBuildRoot
            )
        )
    private val activityRule = ActivityScenarioRule(ScreenshotActivity::class.java)
    private val roboRule =
        RuleChain.outerRule(deviceEmulationRule).around(screenshotRule).around(activityRule)
    private val delegateRule = RuleChain.outerRule(colorsRule).around(roboRule)
    private val isRobolectric = if (Build.FINGERPRINT.contains("robolectric")) true else false

    override fun apply(base: Statement, description: Description): Statement {
        if (isRobolectric) {
            // In robolectric mode, we enable NATIVE graphics and unpack font and icu files.
            // We need to use reflection, as this library is only needed and therefore
            //  only available in deviceless mode.
            val nativeLoaderClassName = "org.robolectric.nativeruntime.DefaultNativeRuntimeLoader"
            val defaultNativeRuntimeLoader = Class.forName(nativeLoaderClassName)
            System.setProperty("robolectric.graphicsMode", "NATIVE")
            defaultNativeRuntimeLoader.getMethod("injectAndLoad").invoke(null)
        }
        val ruleToApply = if (isRobolectric) roboRule else delegateRule
        return ruleToApply.apply(base, description)
    }

    protected fun takeScreenshot(
        mode: Mode = Mode.WrapContent,
        viewProvider: (ComponentActivity) -> View,
        beforeScreenshot: (ComponentActivity) -> Unit = {}
    ): Bitmap {
        activityRule.scenario.onActivity { activity ->
            // Make sure that the activity draws full screen and fits the whole display instead of
            // the system bars.
            val window = activity.window
            window.setDecorFitsSystemWindows(false)

            // Set the content.
            activity.setContentView(viewProvider(activity), mode.layoutParams)

            // Elevation/shadows is not deterministic when doing hardware rendering, so we disable
            // it for any view in the hierarchy.
            window.decorView.removeElevationRecursively()

            activity.currentFocus?.clearFocus()
        }

        // We call onActivity again because it will make sure that our Activity is done measuring,
        // laying out and drawing its content (that we set in the previous onActivity lambda).
        var contentView: View? = null
        activityRule.scenario.onActivity { activity ->
            // Check that the content is what we expected.
            val content = activity.requireViewById<ViewGroup>(android.R.id.content)
            assertEquals(1, content.childCount)
            contentView = content.getChildAt(0)
            beforeScreenshot(activity)
        }

        return if (isRobolectric) {
            contentView?.captureToBitmap()?.get(10, TimeUnit.SECONDS)
                ?: error("timeout while trying to capture view to bitmap")
        } else {
            contentView?.toBitmap() ?: error("contentView is null")
        }
    }

    /**
     * Compare the content of the view provided by [viewProvider] with the golden image identified
     * by [goldenIdentifier] in the context of [emulationSpec].
     */
    fun screenshotTest(
        goldenIdentifier: String,
        mode: Mode = Mode.WrapContent,
        beforeScreenshot: (ComponentActivity) -> Unit = {},
        viewProvider: (ComponentActivity) -> View
    ) {
        val bitmap = takeScreenshot(mode, viewProvider, beforeScreenshot)
        screenshotRule.assertBitmapAgainstGolden(
            bitmap,
            goldenIdentifier,
            matcher,
        )
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
                    val window = checkNotNull(window)
                    // Make sure that the dialog draws full screen and fits the whole display
                    // instead of the system bars.
                    window.setDecorFitsSystemWindows(false)

                    // Disable enter/exit animations.
                    create()
                    window.setWindowAnimations(0)

                    // Elevation/shadows is not deterministic when doing hardware rendering, so we
                    // disable it for any view in the hierarchy.
                    window.decorView.removeElevationRecursively()

                    // Show the dialog.
                    show()
                }
        }

        try {
            val bitmap = dialog?.toBitmap() ?: error("dialog is null")
            screenshotRule.assertBitmapAgainstGolden(
                bitmap,
                goldenIdentifier,
                matcher,
            )
        } finally {
            dialog?.dismiss()
        }
    }

    private fun Dialog.toBitmap(): Bitmap {
        val window = checkNotNull(window)
        return window.decorView.toBitmap(window)
    }

    enum class Mode(val layoutParams: LayoutParams) {
        WrapContent(LayoutParams(WRAP_CONTENT, WRAP_CONTENT)),
        MatchSize(LayoutParams(MATCH_PARENT, MATCH_PARENT)),
        MatchWidth(LayoutParams(MATCH_PARENT, WRAP_CONTENT)),
        MatchHeight(LayoutParams(WRAP_CONTENT, MATCH_PARENT)),
    }
}

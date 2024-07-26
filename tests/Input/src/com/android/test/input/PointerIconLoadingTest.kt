/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.test.input

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.view.ContextThemeWrapper
import android.view.PointerIcon
import android.view.flags.Flags.enableVectorCursorA11ySettings
import android.view.flags.Flags.enableVectorCursors
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import platform.test.screenshot.GoldenPathManager
import platform.test.screenshot.PathConfig
import platform.test.screenshot.ScreenshotTestRule
import platform.test.screenshot.assertAgainstGolden
import platform.test.screenshot.matchers.BitmapMatcher
import platform.test.screenshot.matchers.PixelPerfectMatcher

/**
 * Unit tests for PointerIcon.
 *
 * Run with:
 * atest InputTests:com.android.test.input.PointerIconLoadingTest
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class PointerIconLoadingTest {
    private lateinit var context: Context
    private lateinit var exactScreenshotMatcher: BitmapMatcher

    @get:Rule
    val testName = TestName()

    @get:Rule
    val screenshotRule = ScreenshotTestRule(GoldenPathManager(
        InstrumentationRegistry.getInstrumentation().getContext(),
        ASSETS_PATH,
        TEST_OUTPUT_PATH,
        PathConfig()
    ), disableIconPool = false)

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val config =
            Configuration(context.resources.configuration).apply {
                densityDpi = DENSITY_DPI
                screenWidthDp = SCREEN_WIDTH_DP
                screenHeightDp = SCREEN_HEIGHT_DP
                smallestScreenWidthDp = SCREEN_WIDTH_DP
            }
        context = context.createConfigurationContext(config)

        exactScreenshotMatcher = PixelPerfectMatcher()
    }

    @Test
    fun testPointerFillStyle() {
        assumeTrue(enableVectorCursors())
        assumeTrue(enableVectorCursorA11ySettings())

        val theme: Resources.Theme = context.getResources().newTheme()
        theme.setTo(context.getTheme())
        theme.applyStyle(
            PointerIcon.vectorFillStyleToResource(PointerIcon.POINTER_ICON_VECTOR_STYLE_FILL_GREEN),
            /* force= */ true)
        theme.applyStyle(PointerIcon.vectorStrokeStyleToResource(
            PointerIcon.POINTER_ICON_VECTOR_STYLE_STROKE_WHITE), /* force= */ true)

        val pointerIcon =
            PointerIcon.getLoadedSystemIcon(
                ContextThemeWrapper(context, theme),
                PointerIcon.TYPE_ARROW,
                /* useLargeIcons= */ false,
                /* pointerScale= */ 1f)

        pointerIcon.getBitmap().assertAgainstGolden(
            screenshotRule,
            testName.methodName,
            exactScreenshotMatcher
        )
    }

    @Test
    fun testPointerStrokeStyle() {
        assumeTrue(enableVectorCursors())
        assumeTrue(enableVectorCursorA11ySettings())

        val theme: Resources.Theme = context.getResources().newTheme()
        theme.setTo(context.getTheme())
        theme.applyStyle(
            PointerIcon.vectorFillStyleToResource(PointerIcon.POINTER_ICON_VECTOR_STYLE_FILL_BLACK),
            /* force= */ true)
        theme.applyStyle(PointerIcon.vectorStrokeStyleToResource(
            PointerIcon.POINTER_ICON_VECTOR_STYLE_STROKE_BLACK), /* force= */ true)

        val pointerIcon =
            PointerIcon.getLoadedSystemIcon(
                ContextThemeWrapper(context, theme),
                PointerIcon.TYPE_ARROW,
                /* useLargeIcons= */ false,
                /* pointerScale= */ 1f)

        pointerIcon.getBitmap().assertAgainstGolden(
            screenshotRule,
            testName.methodName,
            exactScreenshotMatcher
        )
    }

    @Test
    fun testPointerScale() {
        assumeTrue(enableVectorCursors())
        assumeTrue(enableVectorCursorA11ySettings())

        val theme: Resources.Theme = context.getResources().newTheme()
        theme.setTo(context.getTheme())
        theme.applyStyle(
            PointerIcon.vectorFillStyleToResource(PointerIcon.POINTER_ICON_VECTOR_STYLE_FILL_BLACK),
            /* force= */ true)
        theme.applyStyle(
            PointerIcon.vectorStrokeStyleToResource(
                PointerIcon.POINTER_ICON_VECTOR_STYLE_STROKE_WHITE),
            /* force= */ true)
        val pointerScale = 2f

        val pointerIcon =
            PointerIcon.getLoadedSystemIcon(
                ContextThemeWrapper(context, theme),
                PointerIcon.TYPE_ARROW,
                /* useLargeIcons= */ false,
                pointerScale)

        pointerIcon.getBitmap().assertAgainstGolden(
            screenshotRule,
            testName.methodName,
            exactScreenshotMatcher
        )
    }

    companion object {
        const val DENSITY_DPI = 160
        const val SCREEN_WIDTH_DP = 480
        const val SCREEN_HEIGHT_DP = 800
        const val ASSETS_PATH = "tests/input/assets"
        val TEST_OUTPUT_PATH =
            "/sdcard/Download/InputTests/" + PointerIconLoadingTest::class.java.simpleName
    }
}

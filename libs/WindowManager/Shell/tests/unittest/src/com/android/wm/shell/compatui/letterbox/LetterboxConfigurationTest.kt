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

package com.android.wm.shell.compatui.letterbox

import android.annotation.ColorRes
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn
import com.android.internal.R
import com.android.wm.shell.ShellTestCase
import java.util.function.Consumer
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn

/**
 * Tests for [LetterboxConfiguration].
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:LetterboxConfigurationTest
 */
@RunWith(AndroidTestingRunner::class)
@SmallTest
class LetterboxConfigurationTest : ShellTestCase() {

    companion object {
        @JvmStatic
        val COLOR_WHITE = Color.valueOf(Color.WHITE)
        @JvmStatic
        val COLOR_RED = Color.valueOf(Color.RED)
        @JvmStatic
        val COLOR_BLACK = Color.valueOf(Color.BLACK)
        @JvmStatic
        val COLOR_WHITE_RESOURCE_ID = android.R.color.white
        @JvmStatic
        val COLOR_BLACK_RESOURCE_ID = android.R.color.black
    }

    @Test
    fun `default background color is used if override is not set`() {
        runTestScenario { r ->
            r.setDefaultBackgroundColorId(COLOR_WHITE_RESOURCE_ID)
            r.loadConfiguration()
            r.checkBackgroundColor(COLOR_WHITE)
        }
    }

    @Test
    fun `overridden background color is used if set`() {
        runTestScenario { r ->
            r.setDefaultBackgroundColorId(COLOR_WHITE_RESOURCE_ID)
            r.loadConfiguration()
            r.overrideBackgroundColor(COLOR_RED)
            r.checkBackgroundColor(COLOR_RED)
        }
    }

    @Test
    fun `overridden background color resource is used if set without override`() {
        runTestScenario { r ->
            r.setDefaultBackgroundColorId(COLOR_WHITE_RESOURCE_ID)
            r.loadConfiguration()
            r.overrideBackgroundColorId(COLOR_BLACK_RESOURCE_ID)
            r.checkBackgroundColor(COLOR_BLACK)
        }
    }

    @Test
    fun `overridden background color has precedence over color id`() {
        runTestScenario { r ->
            r.setDefaultBackgroundColorId(COLOR_WHITE_RESOURCE_ID)
            r.loadConfiguration()
            r.overrideBackgroundColor(COLOR_RED)
            r.overrideBackgroundColorId(COLOR_BLACK_RESOURCE_ID)
            r.checkBackgroundColor(COLOR_RED)
        }
    }

    @Test
    fun `reset background color`() {
        runTestScenario { r ->
            r.setDefaultBackgroundColorId(COLOR_WHITE_RESOURCE_ID)
            r.loadConfiguration()
            r.overrideBackgroundColor(COLOR_RED)
            r.checkBackgroundColor(COLOR_RED)

            r.resetBackgroundColor()
            r.checkBackgroundColor(COLOR_WHITE)

            r.overrideBackgroundColorId(COLOR_BLACK_RESOURCE_ID)
            r.checkBackgroundColor(COLOR_BLACK)

            r.resetBackgroundColor()
            r.checkBackgroundColor(COLOR_WHITE)
        }
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    fun runTestScenario(consumer: Consumer<LetterboxConfigurationRobotTest>) {
        val robot = LetterboxConfigurationRobotTest(mContext)
        consumer.accept(robot)
    }

    class LetterboxConfigurationRobotTest(private val ctx: Context) {

        private val resources: Resources
        private lateinit var letterboxConfig: LetterboxConfiguration

        init {
            resources = ctx.resources
            spyOn(resources)
        }

        fun setDefaultBackgroundColorId(@ColorRes colorId: Int) {
            doReturn(colorId).`when`(resources)
                .getColor(R.color.config_letterboxBackgroundColor, null)
        }

        fun loadConfiguration() {
            letterboxConfig = LetterboxConfiguration(ctx)
        }

        fun overrideBackgroundColor(color: Color) {
            letterboxConfig.setLetterboxBackgroundColor(color)
        }

        fun resetBackgroundColor() {
            letterboxConfig.resetLetterboxBackgroundColor()
        }

        fun overrideBackgroundColorId(@ColorRes colorId: Int) {
            letterboxConfig.setLetterboxBackgroundColorResourceId(colorId)
        }

        fun checkBackgroundColor(expected: Color) {
            val colorComponents = letterboxConfig.getBackgroundColorRgbArray()
            val expectedComponents = expected.components
            assert(expectedComponents.contentEquals(colorComponents))
        }
    }
}

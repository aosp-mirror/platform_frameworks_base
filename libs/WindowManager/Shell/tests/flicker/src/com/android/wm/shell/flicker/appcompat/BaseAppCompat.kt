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

package com.android.wm.shell.flicker.appcompat

import android.content.Context
import android.system.helpers.CommandsHelper
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import com.android.wm.shell.flicker.BaseTest
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.LetterboxAppHelper
import android.tools.device.flicker.legacy.FlickerTestFactory
import android.tools.device.flicker.legacy.IFlickerTestData
import com.android.wm.shell.flicker.appWindowIsVisibleAtEnd
import com.android.wm.shell.flicker.appWindowIsVisibleAtStart
import org.junit.Assume
import org.junit.Before
import org.junit.runners.Parameterized

abstract class BaseAppCompat(flicker: FlickerTest) : BaseTest(flicker) {
    protected val context: Context = instrumentation.context
    protected val letterboxApp = LetterboxAppHelper(instrumentation)
    lateinit var cmdHelper: CommandsHelper
    lateinit var letterboxStyle: HashMap<String, String>

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            setup {
                setStartRotation()
                letterboxApp.launchViaIntent(wmHelper)
                setEndRotation()
            }
        }

    @Before
    fun before() {
        cmdHelper = CommandsHelper.getInstance(instrumentation)
        Assume.assumeTrue(tapl.isTablet && isIgnoreOrientationRequest())
    }

    private fun mapLetterboxStyle(): HashMap<String, String> {
        val res = cmdHelper.executeShellCommand("wm get-letterbox-style")
        val lines = res.lines()
        val map = HashMap<String, String>()
        for (line in lines) {
            val keyValuePair = line.split(":")
            if (keyValuePair.size == 2) {
                val key = keyValuePair[0].trim()
                map[key] = keyValuePair[1].trim()
            }
        }
        return map
    }

    private fun isIgnoreOrientationRequest(): Boolean {
        val res = cmdHelper.executeShellCommand("wm get-ignore-orientation-request")
        return res != null && res.contains("true")
    }

    fun IFlickerTestData.setStartRotation() = setRotation(flicker.scenario.startRotation)

    fun IFlickerTestData.setEndRotation() = setRotation(flicker.scenario.endRotation)

    /** Checks that app entering letterboxed state have rounded corners */
    fun assertLetterboxAppAtStartHasRoundedCorners() {
        assumeLetterboxRoundedCornersEnabled()
        flicker.assertLayersStart { this.hasRoundedCorners(letterboxApp) }
    }

    fun assertLetterboxAppAtEndHasRoundedCorners() {
        assumeLetterboxRoundedCornersEnabled()
        flicker.assertLayersEnd { this.hasRoundedCorners(letterboxApp) }
    }

    /** Only run on tests with config_letterboxActivityCornersRadius != 0 in devices */
    private fun assumeLetterboxRoundedCornersEnabled() {
        if (!::letterboxStyle.isInitialized) {
            letterboxStyle = mapLetterboxStyle()
        }
        Assume.assumeTrue(letterboxStyle.getValue("Corner radius") != "0")
    }

    fun assertLetterboxAppVisibleAtStartAndEnd() {
        flicker.appWindowIsVisibleAtStart(letterboxApp)
        flicker.appWindowIsVisibleAtEnd(letterboxApp)
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestFactory.rotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            return FlickerTestFactory.rotationTests()
        }
    }
}

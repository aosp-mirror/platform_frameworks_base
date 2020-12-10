/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.flicker

import android.content.pm.PackageManager
import android.os.RemoteException
import android.os.SystemClock
import android.platform.helpers.IAppHelper
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.server.wm.flicker.Flicker

/**
 * Base class of all Flicker test that performs common functions for all flicker tests:
 *
 *
 * - Caches transitions so that a transition is run once and the transition results are used by
 * tests multiple times. This is needed for parameterized tests which call the BeforeClass methods
 * multiple times.
 * - Keeps track of all test artifacts and deletes ones which do not need to be reviewed.
 * - Fails tests if results are not available for any test due to jank.
 */
abstract class FlickerTestBase {
    val instrumentation by lazy {
        InstrumentationRegistry.getInstrumentation()
    }
    val uiDevice by lazy {
        UiDevice.getInstance(instrumentation)
    }
    val packageManager: PackageManager by lazy {
        instrumentation.context.getPackageManager()
    }

    /**
     * Build a test tag for the test
     * @param testName Name of the transition(s) being tested
     * @param app App being launcher
     * @param rotation Initial screen rotation
     *
     * @return test tag with pattern <NAME>__<APP>__<ROTATION>
    </ROTATION></APP></NAME> */
    protected fun buildTestTag(testName: String, app: IAppHelper, rotation: Int): String {
        return buildTestTag(
                testName, app, rotation, rotation, app2 = null, extraInfo = "")
    }

    /**
     * Build a test tag for the test
     * @param testName Name of the transition(s) being tested
     * @param app App being launcher
     * @param beginRotation Initial screen rotation
     * @param endRotation End screen rotation (if any, otherwise use same as initial)
     *
     * @return test tag with pattern <NAME>__<APP>__<BEGIN_ROTATION>-<END_ROTATION>
    </END_ROTATION></BEGIN_ROTATION></APP></NAME> */
    protected fun buildTestTag(
        testName: String,
        app: IAppHelper,
        beginRotation: Int,
        endRotation: Int
    ): String {
        return buildTestTag(
                testName, app, beginRotation, endRotation, app2 = null, extraInfo = "")
    }

    /**
     * Build a test tag for the test
     * @param testName Name of the transition(s) being tested
     * @param app App being launcher
     * @param app2 Second app being launched (if any)
     * @param beginRotation Initial screen rotation
     * @param endRotation End screen rotation (if any, otherwise use same as initial)
     * @param extraInfo Additional information to append to the tag
     *
     * @return test tag with pattern <NAME>__<APP></APP>(S)>__<ROTATION></ROTATION>(S)>[__<EXTRA>]
    </EXTRA></NAME> */
    protected fun buildTestTag(
        testName: String,
        app: IAppHelper,
        beginRotation: Int,
        endRotation: Int,
        app2: IAppHelper?,
        extraInfo: String
    ): String {
        var testTag = "${testName}__${app.launcherName}"
        if (app2 != null) {
            testTag += "-${app2.launcherName}"
        }
        testTag += "__${Surface.rotationToString(beginRotation)}"
        if (endRotation != beginRotation) {
            testTag += "-${Surface.rotationToString(endRotation)}"
        }
        if (extraInfo.isNotEmpty()) {
            testTag += "__$extraInfo"
        }
        return testTag
    }

    protected fun Flicker.setRotation(rotation: Int) {
        try {
            when (rotation) {
                Surface.ROTATION_270 -> device.setOrientationLeft()
                Surface.ROTATION_90 -> device.setOrientationRight()
                Surface.ROTATION_0 -> device.setOrientationNatural()
                else -> device.setOrientationNatural()
            }
            // Wait for animation to complete
            SystemClock.sleep(1000)
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
    }

    companion object {
        const val NAVIGATION_BAR_WINDOW_TITLE = "NavigationBar"
        const val STATUS_BAR_WINDOW_TITLE = "StatusBar"
        const val DOCKED_STACK_DIVIDER = "DockedStackDivider"
        const val SPLIT_DIVIDER = "SplitDivider"
        const val IMAGE_WALLPAPER = "ImageWallpaper"
    }
}

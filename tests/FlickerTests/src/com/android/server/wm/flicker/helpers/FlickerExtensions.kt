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

package com.android.server.wm.flicker.helpers

import android.os.Bundle
import android.os.RemoteException
import android.platform.helpers.IAppHelper
import android.view.Surface
import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.startRotation

/**
 * Changes the device [rotation] and wait for the rotation animation to complete
 *
 * @param rotation New device rotation
 */
fun Flicker.setRotation(rotation: Int) {
    try {
        when (rotation) {
            Surface.ROTATION_270 -> device.setOrientationRight()
            Surface.ROTATION_90 -> device.setOrientationLeft()
            Surface.ROTATION_0 -> device.setOrientationNatural()
            else -> device.setOrientationNatural()
        }

        wmHelper.waitForRotation(rotation)
        wmHelper.waitForNavBarStatusBarVisible()
        wmHelper.waitForAppTransitionIdle()
    } catch (e: RemoteException) {
        throw RuntimeException(e)
    }
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
fun buildTestTag(
    testName: String,
    app: IAppHelper,
    beginRotation: Int,
    endRotation: Int
): String {
    return buildTestTag(
        testName, app.launcherName, beginRotation, endRotation, app2 = null, extraInfo = "")
}

/**
 * Build a test tag for the test
 * @param testName Name of the transition(s) being tested
 * @param app App being launcher
 * @param configuration Configuration for the test
 *
 * @return test tag with pattern <NAME>__<APP>__<BEGIN_ROTATION>-<END_ROTATION>
</END_ROTATION></BEGIN_ROTATION></APP></NAME> */
@JvmOverloads
fun buildTestTag(
    testName: String,
    configuration: Bundle,
    extraInfo: String = ""
): String {
    return buildTestTag(testName,
        app = null,
        beginRotation = configuration.startRotation,
        endRotation = configuration.endRotation,
        app2 = null,
        extraInfo = extraInfo)
}

/**
 * Build a test tag for the test
 * @param testName Name of the transition(s) being tested
 * @param app App being launcher
 * @param configuration Configuration for the test
 *
 * @return test tag with pattern <NAME>__<APP>__<BEGIN_ROTATION>-<END_ROTATION>
</END_ROTATION></BEGIN_ROTATION></APP></NAME> */
@JvmOverloads
fun buildTestTag(
    testName: String,
    app: IAppHelper?,
    configuration: Bundle,
    extraInfo: String = ""
): String {
    return buildTestTag(testName, app?.launcherName ?: "", configuration.startRotation,
        configuration.endRotation, app2 = null, extraInfo = extraInfo)
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
fun buildTestTag(
    testName: String,
    app: String?,
    beginRotation: Int,
    endRotation: Int,
    app2: String?,
    extraInfo: String
): String {
    var testTag = testName
    if (app != null) {
        testTag += "__$app"
    }
    if (app2 != null) {
        testTag += "-$app2"
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

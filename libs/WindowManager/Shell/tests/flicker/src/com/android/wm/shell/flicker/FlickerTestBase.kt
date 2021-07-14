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

import android.app.Instrumentation
import android.content.pm.PackageManager
import android.content.pm.PackageManager.FEATURE_LEANBACK
import android.content.pm.PackageManager.FEATURE_LEANBACK_ONLY
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.runners.Parameterized

/**
 * Base class of all Flicker test that performs common functions for all flicker tests:
 *
 * - Caches transitions so that a transition is run once and the transition results are used by
 * tests multiple times. This is needed for parameterized tests which call the BeforeClass methods
 * multiple times.
 * - Keeps track of all test artifacts and deletes ones which do not need to be reviewed.
 * - Fails tests if results are not available for any test due to jank.
 */
abstract class FlickerTestBase(
    protected val rotationName: String,
    protected val rotation: Int
) {
    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val uiDevice = UiDevice.getInstance(instrumentation)
    val packageManager: PackageManager = instrumentation.context.packageManager
    protected val isTelevision: Boolean by lazy {
        packageManager.run {
            hasSystemFeature(FEATURE_LEANBACK) || hasSystemFeature(FEATURE_LEANBACK_ONLY)
        }
    }

    /**
     * By default WmShellFlickerTests do not run on TV devices.
     * If the test should run on TV - it should override this method.
     */
    @Before
    open fun televisionSetUp() = assumeFalse(isTelevision)

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0, Surface.ROTATION_90)
            return supportedRotations.map { arrayOf(Surface.rotationToString(it), it) }
        }
    }
}

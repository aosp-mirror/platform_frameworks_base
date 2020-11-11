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

package com.android.wm.shell.flicker.pip.tv

import android.content.pm.PackageManager.FEATURE_LEANBACK
import android.content.pm.PackageManager.FEATURE_LEANBACK_ONLY
import android.view.Surface.ROTATION_0
import android.view.Surface.rotationToString
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.wm.shell.flicker.pip.PipTestBase
import org.junit.After
import org.junit.Assume
import org.junit.Before

abstract class TvPipTestBase(rotationName: String, rotation: Int)
    : PipTestBase(rotationName, rotation) {

    private val isTelevision: Boolean
        get() = packageManager.run {
            hasSystemFeature(FEATURE_LEANBACK) || hasSystemFeature(FEATURE_LEANBACK_ONLY)
        }

    @Before
    open fun setUp() {
        Assume.assumeTrue(isTelevision)
        uiDevice.wakeUpAndGoToHomeScreen()
    }

    @After
    open fun tearDown() {
        testApp.forceStop()
    }

    protected fun fail(message: String): Nothing = throw AssertionError(message)

    companion object {
        @JvmStatic
        protected val rotationParams: Collection<Array<Any>> =
                listOf(arrayOf(rotationToString(ROTATION_0), ROTATION_0))
    }
}
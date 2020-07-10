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

package com.android.server.wm.flicker.launch

import androidx.test.filters.FlakyTest
import androidx.test.filters.LargeTest
import com.android.server.wm.flicker.CommonTransitions
import com.android.server.wm.flicker.LayersTraceSubject
import com.android.server.wm.flicker.NonRotationTestBase
import com.android.server.wm.flicker.StandardAppHelper
import com.android.server.wm.flicker.TransitionRunner
import com.android.server.wm.flicker.WmTraceSubject
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launch app from launcher.
 * To run this test: `atest FlickerTests:OpenAppColdTest`
 */
@LargeTest
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenAppColdTest(
    beginRotationName: String,
    beginRotation: Int
) : NonRotationTestBase(beginRotationName, beginRotation) {
    init {
        testApp = StandardAppHelper(instrumentation,
                "com.android.server.wm.flicker.testapp", "SimpleApp")
    }

    override val transitionToRun: TransitionRunner
        get() = CommonTransitions.openAppCold(testApp, instrumentation, uiDevice, beginRotation)
                .includeJankyRuns().build()

    @Test
    fun checkVisibility_wallpaperWindowBecomesInvisible() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .showsBelowAppWindow("Wallpaper")
                    .then()
                    .hidesBelowAppWindow("Wallpaper")
                    .forAllEntries()
        }
    }

    @FlakyTest(bugId = 140855415)
    @Ignore("Waiting bug feedback")
    @Test
    fun checkZOrder_appWindowReplacesLauncherAsTopWindow() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .showsAppWindowOnTop(
                            "com.android.launcher3/.Launcher")
                    .then()
                    .showsAppWindowOnTop(testApp.getPackage())
                    .forAllEntries()
        }
    }

    @Test
    fun checkVisibility_wallpaperLayerBecomesInvisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .showsLayer("Wallpaper")
                    .then()
                    .replaceVisibleLayer("Wallpaper", testApp.getPackage())
                    .forAllEntries()
        }
    }
}
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

import com.android.server.wm.flicker.NonRotationTestBase
import com.android.server.wm.flicker.StandardAppHelper
import com.android.server.wm.flicker.dsl.LayersAssertion
import com.android.server.wm.flicker.dsl.WmAssertion

abstract class OpenAppTestBase(
    rotationName: String,
    rotation: Int
) : NonRotationTestBase(rotationName, rotation) {
    protected val testApp = StandardAppHelper(instrumentation,
            "com.android.server.wm.flicker.testapp", "SimpleApp")

    protected fun WmAssertion.wallpaperWindowBecomesInvisible(
        bugId: Int = 0,
        enabled: Boolean = bugId == 0
    ) {
        all("wallpaperWindowBecomesInvisible", enabled, bugId) {
            this.showsBelowAppWindow("Wallpaper")
                    .then()
                    .hidesBelowAppWindow("Wallpaper")
        }
    }

    protected fun WmAssertion.appWindowReplacesLauncherAsTopWindow(
        bugId: Int = 0,
        enabled: Boolean = bugId == 0
    ) {
        all("appWindowReplacesLauncherAsTopWindow", enabled, bugId) {
            this.showsAppWindowOnTop(
                    "Launcher")
                    .then()
                    .showsAppWindowOnTop(testApp.getPackage())
        }
    }

    protected fun LayersAssertion.wallpaperLayerBecomesInvisible(
        bugId: Int = 0,
        enabled: Boolean = bugId == 0
    ) {
        all("appWindowReplacesLauncherAsTopWindow", enabled, bugId) {
            this.showsLayer("Wallpaper")
                    .then()
                    .replaceVisibleLayer("Wallpaper", testApp.getPackage())
        }
    }
}
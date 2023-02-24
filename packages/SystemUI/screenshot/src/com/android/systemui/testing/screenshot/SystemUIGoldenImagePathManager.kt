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

import androidx.test.platform.app.InstrumentationRegistry
import platform.test.screenshot.GoldenImagePathManager
import platform.test.screenshot.PathConfig

/** A [GoldenImagePathManager] that should be used for all SystemUI screenshot tests. */
class SystemUIGoldenImagePathManager(
    pathConfig: PathConfig,
    override val assetsPathRelativeToRepo: String = "tests/screenshot/assets"
) :
    GoldenImagePathManager(
        appContext = InstrumentationRegistry.getInstrumentation().context,
        assetsPathRelativeToRepo = assetsPathRelativeToRepo,
        deviceLocalPath =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .filesDir
                .absolutePath
                .toString() + "/sysui_screenshots",
        pathConfig = pathConfig,
    ) {
    override fun toString(): String {
        // This string is appended to all actual/expected screenshots on the device, so make sure
        // it is a static value.
        return "SystemUIGoldenImagePathManager"
    }
}

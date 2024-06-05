/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.input.screenshot

import androidx.test.platform.app.InstrumentationRegistry
import platform.test.screenshot.GoldenPathManager
import platform.test.screenshot.PathConfig

/** A [GoldenPathManager] that should be used for all Input screenshot tests. */
class InputGoldenPathManager(pathConfig: PathConfig, assetsPathRelativeToBuildRoot: String) :
    GoldenPathManager(
        appContext = InstrumentationRegistry.getInstrumentation().context,
        assetsPathRelativeToBuildRoot = assetsPathRelativeToBuildRoot,
        deviceLocalPath =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .filesDir
                .absolutePath
                .toString() + "/input_screenshots",
        pathConfig = pathConfig,
    ) {
    override fun toString(): String {
        // This string is appended to all actual/expected screenshots on the device, so make sure
        // it is a static value.
        return "InputGoldenPathManager"
    }
}

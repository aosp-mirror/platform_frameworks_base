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

package com.android.wm.shell.flicker.legacysplitscreen

import com.android.wm.shell.flicker.NonRotationTestBase
import com.android.wm.shell.flicker.TEST_APP_NONRESIZEABLE_LABEL
import com.android.wm.shell.flicker.TEST_APP_SPLITSCREEN_PRIMARY_LABEL
import com.android.wm.shell.flicker.TEST_APP_SPLITSCREEN_SECONDARY_LABEL
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import com.android.wm.shell.flicker.testapp.Components

abstract class SplitScreenTestBase(
    rotationName: String,
    rotation: Int
) : NonRotationTestBase(rotationName, rotation) {
    protected val splitScreenApp = SplitScreenHelper(instrumentation,
            TEST_APP_SPLITSCREEN_PRIMARY_LABEL,
            Components.SplitScreenActivity())
    protected val secondaryApp = SplitScreenHelper(instrumentation,
            TEST_APP_SPLITSCREEN_SECONDARY_LABEL,
            Components.SplitScreenSecondaryActivity())
    protected val nonResizeableApp = SplitScreenHelper(instrumentation,
            TEST_APP_NONRESIZEABLE_LABEL,
            Components.NonResizeableActivity())
}

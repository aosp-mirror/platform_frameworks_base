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

package com.android.wm.shell.flicker.pip

import com.android.wm.shell.flicker.FlickerTestBase
import com.android.wm.shell.flicker.helpers.PipAppHelper
import org.junit.Before

abstract class PipTestBase(
    rotationName: String,
    rotation: Int
) : FlickerTestBase(rotationName, rotation) {
    protected val testApp = PipAppHelper(instrumentation)

    @Before
    override fun televisionSetUp() {
        /**
         * The super implementation assumes ([org.junit.Assume]) that not running on TV, thus
         * disabling the test on TV. This test, however, *should run on TV*, so we overriding this
         * method and simply leaving it blank.
         */
    }
}

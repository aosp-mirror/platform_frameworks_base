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

package com.android.server.wm.flicker.pip

import com.android.server.wm.flicker.dsl.LayersAssertion
import com.android.server.wm.flicker.NonRotationTestBase
import com.android.server.wm.flicker.dsl.WmAssertion
import com.android.server.wm.flicker.helpers.PipAppHelper

abstract class PipTestBase(
    rotationName: String,
    rotation: Int
) : NonRotationTestBase(rotationName, rotation) {
    protected val testApp = PipAppHelper(instrumentation)

    protected fun WmAssertion.pipWindowBecomesVisible() {
        all("pipWindowBecomesVisible") {
            this.skipUntilFirstAssertion()
                    .showsAppWindowOnTop(sPipWindowTitle)
                    .then()
                    .hidesAppWindow(sPipWindowTitle)
        }
    }

    protected fun LayersAssertion.pipLayerBecomesVisible() {
        all("pipLayerBecomesVisible") {
            this.skipUntilFirstAssertion()
                    .showsLayer(sPipWindowTitle)
                    .then()
                    .hidesLayer(sPipWindowTitle)
        }
    }

    companion object {
        const val sPipWindowTitle = "PipMenuActivity"
    }
}
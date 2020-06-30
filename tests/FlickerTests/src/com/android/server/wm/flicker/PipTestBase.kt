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

package com.android.server.wm.flicker

import androidx.test.InstrumentationRegistry
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.UiDevice
import com.android.server.wm.flicker.helpers.AutomationUtils
import com.android.server.wm.flicker.helpers.PipAppHelper
import org.junit.AfterClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

@LargeTest
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
abstract class PipTestBase(
    beginRotationName: String,
    beginRotation: Int
) : NonRotationTestBase(beginRotationName, beginRotation) {
    init {
        testApp = PipAppHelper(instrumentation)
    }

    @Test
    fun checkVisibility_pipWindowBecomesVisible() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .skipUntilFirstAssertion()
                    .showsAppWindowOnTop(sPipWindowTitle)
                    .then()
                    .hidesAppWindow(sPipWindowTitle)
                    .forAllEntries()
        }
    }

    @Test
    fun checkVisibility_pipLayerBecomesVisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .skipUntilFirstAssertion()
                    .showsLayer(sPipWindowTitle)
                    .then()
                    .hidesLayer(sPipWindowTitle)
                    .forAllEntries()
        }
    }

    companion object {
        const val sPipWindowTitle = "PipMenuActivity"

        @AfterClass
        @JvmStatic
        fun teardown() {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            if (AutomationUtils.hasPipWindow(device)) {
                AutomationUtils.closePipWindow(device)
            }
        }
    }
}

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

package com.android.server.wm.flicker.ime

import androidx.test.filters.LargeTest
import com.android.server.wm.flicker.CommonTransitions
import com.android.server.wm.flicker.LayersTraceSubject
import com.android.server.wm.flicker.NonRotationTestBase
import com.android.server.wm.flicker.TransitionRunner
import com.android.server.wm.flicker.WmTraceSubject
import com.android.server.wm.flicker.helpers.ImeAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window opening transitions.
 * To run this test: `atest FlickerTests:OpenImeWindowTest`
 */
@LargeTest
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenImeWindowTest(
    beginRotationName: String,
    beginRotation: Int
) : NonRotationTestBase(beginRotationName, beginRotation) {
    init {
        testApp = ImeAppHelper(instrumentation)
    }

    override val transitionToRun: TransitionRunner
        get() = CommonTransitions.editTextSetFocus(testApp as ImeAppHelper,
                instrumentation, uiDevice, beginRotation)
                .includeJankyRuns().build()

    @Test
    fun checkVisibility_imeWindowBecomesVisible() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .skipUntilFirstAssertion()
                    .hidesNonAppWindow(IME_WINDOW_TITLE)
                    .then()
                    .showsNonAppWindow(IME_WINDOW_TITLE)
                    .forAllEntries()
        }
    }

    @Test
    fun checkVisibility_imeLayerBecomesVisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .hidesLayer(IME_WINDOW_TITLE)
                    .then()
                    .showsLayer(IME_WINDOW_TITLE)
                    .forAllEntries()
        }
    }

    companion object {
        private const val IME_WINDOW_TITLE = "InputMethod"
    }
}

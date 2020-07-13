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

import android.view.Surface
import androidx.test.filters.LargeTest
import com.android.server.wm.flicker.NonRotationTestBase
import com.android.server.wm.flicker.dsl.flicker
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
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
    rotationName: String,
    rotation: Int
) : NonRotationTestBase(rotationName, rotation) {
    @Test
    fun test() {
        val testApp = ImeAppHelper(instrumentation)

        flicker(instrumentation) {
            withTag { buildTestTag("openIme", testApp, rotation) }
            repeat { 1 }
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                    this.setRotation(rotation)
                    testApp.open()
                }
            }
            transitions {
                testApp.openIME(device)
            }
            teardown {
                eachRun {
                    testApp.closeIME(device)
                }
                test {
                    testApp.exit()
                    this.setRotation(Surface.ROTATION_0)
                }
            }
            assertions {
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()

                    all("imeWindowBecomesVisible") {
                        this.skipUntilFirstAssertion()
                            .hidesNonAppWindow(IME_WINDOW_TITLE)
                            .then()
                            .showsNonAppWindow(IME_WINDOW_TITLE)
                    }
                }

                layersTrace {
                    navBarLayerIsAlwaysVisible()
                    statusBarLayerIsAlwaysVisible()
                    noUncoveredRegions(rotation)
                    navBarLayerRotatesAndScales(rotation)
                    statusBarLayerRotatesScales(rotation)

                    imeLayerBecomesVisible()
                }
            }
        }
    }

    companion object {
        private const val IME_WINDOW_TITLE = "InputMethod"
    }
}

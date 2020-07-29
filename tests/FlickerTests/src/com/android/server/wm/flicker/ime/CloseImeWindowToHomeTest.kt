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
import com.android.server.wm.flicker.helpers.openQuickstep
import com.android.server.wm.flicker.helpers.reopenAppFromOverview
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
 * Test IME window closing to home transitions.
 * To run this test: `atest FlickerTests:CloseImeWindowToHomeTest`
 */
@LargeTest
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class CloseImeWindowToHomeTest(
    rotationName: String,
    rotation: Int
) : NonRotationTestBase(rotationName, rotation) {
    open val testApp = ImeAppHelper(instrumentation)

    @Test
    open fun test() {
        flicker(instrumentation) {
            withTag { buildTestTag("imeToHome", testApp, rotation) }
            repeat { 1 }
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                    this.setRotation(rotation)
                    testApp.open()
                }
                eachRun {
                    device.openQuickstep()
                    device.reopenAppFromOverview()
                    this.setRotation(rotation)
                    testApp.openIME(device)
                }
            }
            transitions {
                device.pressHome()
                device.waitForIdle()
            }
            teardown {
                eachRun {
                    device.pressHome()
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
                    imeWindowBecomesInvisible()
                    imeAppWindowBecomesInvisible(testApp)
                }

                layersTrace {
                    navBarLayerIsAlwaysVisible()
                    statusBarLayerIsAlwaysVisible()
                    noUncoveredRegions(rotation)
                    navBarLayerRotatesAndScales(rotation, Surface.ROTATION_0)
                    statusBarLayerRotatesScales(rotation, Surface.ROTATION_0)
                    imeLayerBecomesInvisible(bugId = 153739621)
                    imeAppLayerBecomesInvisible(testApp, bugId = 153739621)
                }
            }
        }
    }
}
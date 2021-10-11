/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.flicker.bubble

import androidx.test.filters.RequiresDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Test launching a new activity from bubble.
 *
 * To run this test: `atest WMShellFlickerTests:ExpandBubbleScreen`
 *
 * Actions:
 *     Launch an app and enable app's bubble notification
 *     Send a bubble notification
 *     The activity for the bubble is launched
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@Group4
class ExpandBubbleScreen(testSpec: FlickerTestParameter) : BaseBubbleScreen(testSpec) {

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = buildTransition() {
            setup {
                test {
                    addBubbleBtn?.run { addBubbleBtn.click() } ?: error("Bubble widget not found")
                }
            }
            transitions {
                val showBubble = device.wait(Until.findObject(
                        By.res("com.android.systemui", "bubble_view")), FIND_OBJECT_TIMEOUT)
                showBubble?.run { showBubble.click() } ?: error("Bubble notify not found")
                device.pressBack()
            }
        }
}

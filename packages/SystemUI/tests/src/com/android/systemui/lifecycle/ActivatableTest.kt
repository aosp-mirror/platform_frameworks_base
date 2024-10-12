/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.lifecycle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ActivatableTest : SysuiTestCase() {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rememberActivated() {
        val keepAliveMutable = mutableStateOf(true)
        var isActive = false
        composeRule.setContent {
            val keepAlive by keepAliveMutable
            if (keepAlive) {
                rememberActivated("test") {
                    FakeActivatable(
                        onActivation = { isActive = true },
                        onDeactivation = { isActive = false },
                    )
                }
            }
        }
        assertThat(isActive).isTrue()
    }

    @Test
    fun rememberActivated_leavingTheComposition() {
        val keepAliveMutable = mutableStateOf(true)
        var isActive = false
        composeRule.setContent {
            val keepAlive by keepAliveMutable
            if (keepAlive) {
                rememberActivated("name") {
                    FakeActivatable(
                        onActivation = { isActive = true },
                        onDeactivation = { isActive = false },
                    )
                }
            }
        }

        // Tear down the composable.
        composeRule.runOnUiThread { keepAliveMutable.value = false }
        composeRule.waitForIdle()

        assertThat(isActive).isFalse()
    }
}

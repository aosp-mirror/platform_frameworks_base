/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.compose.gesture

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.modifiers.thenIf
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NestedScrollControllerTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun nestedScrollController() {
        val state = NestedScrollControlState()
        var nestedScrollConsumesDelta = false
        rule.setContent {
            Box(
                Modifier.fillMaxSize()
                    .nestedScrollController(state)
                    .scrollable(
                        rememberScrollableState { if (nestedScrollConsumesDelta) it else 0f },
                        Orientation.Vertical,
                    )
            )
        }

        // If the nested child does not consume scrolls, then outer scrolling is allowed.
        assertThat(state.isOuterScrollAllowed).isTrue()
        nestedScrollConsumesDelta = false
        rule.onRoot().performTouchInput {
            down(topLeft)
            moveBy(Offset(0f, bottom))
        }
        assertThat(state.isOuterScrollAllowed).isTrue()
        rule.onRoot().performTouchInput { up() }

        // If the nested child consumes scrolls, then outer scrolling is disabled.
        nestedScrollConsumesDelta = true
        rule.onRoot().performTouchInput {
            down(topLeft)
            moveBy(Offset(0f, bottom))
        }
        assertThat(state.isOuterScrollAllowed).isFalse()

        // Outer scrolling is enabled again when stopping the scroll.
        rule.onRoot().performTouchInput { up() }
        assertThat(state.isOuterScrollAllowed).isTrue()
    }

    @Test
    fun nestedScrollController_detached() {
        val state = NestedScrollControlState()
        var composeNestedScroll by mutableStateOf(true)
        rule.setContent {
            val scrollableState = rememberScrollableState { it }
            Box(
                Modifier.fillMaxSize().thenIf(composeNestedScroll) {
                    Modifier.nestedScrollController(state)
                        .scrollable(scrollableState, Orientation.Vertical)
                }
            )
        }
        // The nested child consumes scrolls, so outer scrolling is disabled.
        rule.onRoot().performTouchInput {
            down(topLeft)
            moveBy(Offset(0f, bottom))
        }
        assertThat(state.isOuterScrollAllowed).isFalse()

        // Outer scrolling is enabled again when removing the controller from composition.
        composeNestedScroll = false
        rule.waitForIdle()
        assertThat(state.isOuterScrollAllowed).isTrue()
    }
}

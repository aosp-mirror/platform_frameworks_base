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

package com.android.settingslib.spa.lifecycle

import androidx.compose.material3.Text
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.waitUntilExists
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlowExtTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun collectAsCallbackWithLifecycle() {
        val flow = flowOf(TEXT)

        composeTestRule.setContent {
            val callback = flow.collectAsCallbackWithLifecycle()
            Text(callback() ?: "")
        }

        composeTestRule.waitUntilExists(hasText(TEXT))
    }

    @Test
    fun collectAsCallbackWithLifecycle_changed() {
        val flow = MutableStateFlow(TEXT)

        composeTestRule.setContent {
            val callback = flow.collectAsCallbackWithLifecycle()
            Text(callback() ?: "")
        }
        flow.value = NEW_TEXT

        composeTestRule.waitUntilExists(hasText(NEW_TEXT))
    }

    private companion object {
        const val TEXT = "Text"
        const val NEW_TEXT = "New Text"
    }
}

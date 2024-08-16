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

package com.android.compose.animation.scene.modifiers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.test.assertSizeIsEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SizeMatcherTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun sizeMatcher() {
        val contentSize = DpSize(200.dp, 100.dp)
        val sizeMatcher = SizeMatcher()
        val backgroundTag = "background"

        rule.setContent {
            Box {
                Box(Modifier.sizeMatcherSource(sizeMatcher).size(contentSize))
                Box(Modifier.testTag(backgroundTag).sizeMatcherDestination(sizeMatcher))
            }
        }

        rule.onNodeWithTag(backgroundTag).assertSizeIsEqualTo(contentSize.width, contentSize.height)
    }
}

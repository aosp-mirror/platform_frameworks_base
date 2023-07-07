/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.spa.framework.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.util.URLSPAN_TAG
import com.android.settingslib.spa.framework.util.annotatedStringResource
import com.android.settingslib.spa.test.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnnotatedStringResourceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testAnnotatedStringResource() {
        composeTestRule.setContent {
            val annotatedString = annotatedStringResource(R.string.test_annotated_string_resource, Color.Blue)

            val annotations = annotatedString.getStringAnnotations(0, annotatedString.length)
            assertThat(annotations).hasSize(1)
            assertThat(annotations[0].start).isEqualTo(31)
            assertThat(annotations[0].end).isEqualTo(35)
            assertThat(annotations[0].tag).isEqualTo(URLSPAN_TAG)
            assertThat(annotations[0].item).isEqualTo("https://www.google.com/")

            assertThat(annotatedString.spanStyles).hasSize(2)
            assertThat(annotatedString.spanStyles[0].start).isEqualTo(22)
            assertThat(annotatedString.spanStyles[0].end).isEqualTo(26)
            assertThat(annotatedString.spanStyles[0].item).isEqualTo(
                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Normal))

            assertThat(annotatedString.spanStyles[1].start).isEqualTo(31)
            assertThat(annotatedString.spanStyles[1].end).isEqualTo(35)
            assertThat(annotatedString.spanStyles[1].item).isEqualTo(SpanStyle(color = Color.Blue))
        }
    }
}

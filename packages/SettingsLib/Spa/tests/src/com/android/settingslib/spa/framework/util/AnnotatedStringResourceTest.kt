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

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            val annotatedString =
                annotatedStringResource(R.string.test_annotated_string_resource)

            val annotations = annotatedString.getStringAnnotations(0, annotatedString.length)
            assertThat(annotations).containsExactly(
                AnnotatedString.Range(
                    item = "https://www.android.com/",
                    start = 31,
                    end = 35,
                    tag = URL_SPAN_TAG,
                )
            )

            assertThat(annotatedString.spanStyles).containsExactly(
                AnnotatedString.Range(
                    item = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Normal),
                    start = 22,
                    end = 26,
                ),
                AnnotatedString.Range(
                    item = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                    start = 31,
                    end = 35,
                ),
            )
        }
    }
}

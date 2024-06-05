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

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.test.R
import com.android.settingslib.spa.widget.ui.AnnotatedText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class AnnotatedTextTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var uriHandler: UriHandler

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun text_isDisplayed() {
        composeTestRule.setContent {
            AnnotatedText(R.string.test_annotated_string_resource)
        }

        composeTestRule.onNodeWithText(context.getString(R.string.test_annotated_string_resource))
            .assertIsDisplayed()
    }

    @Test
    fun onUriClick_openUri() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                AnnotatedText(R.string.test_link)
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.test_link)).performClick()

        verify(uriHandler).openUri("https://www.android.com/")
    }
}

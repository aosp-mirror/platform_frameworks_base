/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.framework.theme

import android.content.Context
import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class SettingsThemeTest {
    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @get:Rule
    val composeTestRule = createComposeRule()

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var resources: Resources

    private var nextMockResId = 1

    @Before
    fun setUp() {
        whenever(context.resources).thenReturn(resources)
        whenever(resources.getString(any())).thenReturn("")
    }

    private fun mockAndroidConfig(configName: String, configValue: String) {
        whenever(resources.getIdentifier(configName, "string", "android"))
            .thenReturn(nextMockResId)
        whenever(resources.getString(nextMockResId)).thenReturn(configValue)
        nextMockResId++
    }

    @Test
    fun noFontFamilyConfig_useDefaultFontFamily() {
        val fontFamily = getFontFamily()

        assertThat(fontFamily.headlineLarge.fontFamily).isSameInstanceAs(FontFamily.Default)
        assertThat(fontFamily.bodyLarge.fontFamily).isSameInstanceAs(FontFamily.Default)
    }

    @Test
    fun hasFontFamilyConfig_useConfiguredFontFamily() {
        mockAndroidConfig("config_headlineFontFamily", "HeadlineNormal")
        mockAndroidConfig("config_headlineFontFamilyMedium", "HeadlineMedium")
        mockAndroidConfig("config_bodyFontFamily", "BodyNormal")
        mockAndroidConfig("config_bodyFontFamilyMedium", "BodyMedium")

        val fontFamily = getFontFamily()

        val headlineFontFamily = fontFamily.headlineLarge.fontFamily.toString()
        assertThat(headlineFontFamily).contains("HeadlineNormal")
        assertThat(headlineFontFamily).contains("HeadlineMedium")
        val bodyFontFamily = fontFamily.bodyLarge.fontFamily.toString()
        assertThat(bodyFontFamily).contains("BodyNormal")
        assertThat(bodyFontFamily).contains("BodyMedium")
    }

    private fun getFontFamily(): Typography {
        lateinit var typography: Typography
        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                SettingsTheme {
                    typography = MaterialTheme.typography
                }
            }
        }
        return typography
    }
}

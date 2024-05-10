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

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private class SettingsTypography(settingsFontFamily: SettingsFontFamily) {
    private val brand = settingsFontFamily.brand
    private val plain = settingsFontFamily.plain

    val typography = Typography(
        displayLarge = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 57.sp,
            lineHeight = 64.sp,
            letterSpacing = (-0.2).sp,
            hyphens = Hyphens.Auto,
        ),
        displayMedium = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 45.sp,
            lineHeight = 52.sp,
            letterSpacing = 0.0.sp,
            hyphens = Hyphens.Auto,
        ),
        displaySmall = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 36.sp,
            lineHeight = 44.sp,
            letterSpacing = 0.0.sp,
            hyphens = Hyphens.Auto,
        ),
        headlineLarge = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = 0.0.sp,
            hyphens = Hyphens.Auto,
        ),
        headlineMedium = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            letterSpacing = 0.0.sp,
            hyphens = Hyphens.Auto,
        ),
        headlineSmall = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.0.sp,
            hyphens = Hyphens.Auto,
        ),
        titleLarge = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.02.em,
            hyphens = Hyphens.Auto,
        ),
        titleMedium = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 20.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.02.em,
            hyphens = Hyphens.Auto,
        ),
        titleSmall = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 18.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.02.em,
            hyphens = Hyphens.Auto,
        ),
        bodyLarge = TextStyle(
            fontFamily = plain,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.01.em,
            hyphens = Hyphens.Auto,
        ),
        bodyMedium = TextStyle(
            fontFamily = plain,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.01.em,
            hyphens = Hyphens.Auto,
        ),
        bodySmall = TextStyle(
            fontFamily = plain,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.01.em,
            hyphens = Hyphens.Auto,
        ),
        labelLarge = TextStyle(
            fontFamily = plain,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.01.em,
            hyphens = Hyphens.Auto,
        ),
        labelMedium = TextStyle(
            fontFamily = plain,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.01.em,
            hyphens = Hyphens.Auto,
        ),
        labelSmall = TextStyle(
            fontFamily = plain,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.01.em,
            hyphens = Hyphens.Auto,
        ),
    )
}

@Composable
internal fun rememberSettingsTypography(): Typography {
    val settingsFontFamily = rememberSettingsFontFamily()
    return remember { SettingsTypography(settingsFontFamily).typography }
}

/** Creates a new [TextStyle] which font weight set to medium. */
internal fun TextStyle.toMediumWeight() =
    copy(fontWeight = FontWeight.Medium, letterSpacing = 0.01.em)

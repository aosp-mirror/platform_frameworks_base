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

@file:OptIn(ExperimentalTextApi::class)

package com.android.settingslib.spa.framework.theme

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.android.settingslib.spa.framework.compose.rememberContext

internal data class SettingsFontFamily(
    val brand: FontFamily,
    val plain: FontFamily,
)

private fun Context.getSettingsFontFamily(): SettingsFontFamily {
    return SettingsFontFamily(
        brand = getFontFamily(
            configFontFamilyNormal = "config_headlineFontFamily",
            configFontFamilyMedium = "config_headlineFontFamilyMedium",
        ),
        plain = getFontFamily(
            configFontFamilyNormal = "config_bodyFontFamily",
            configFontFamilyMedium = "config_bodyFontFamilyMedium",
        ),
    )
}

private fun Context.getFontFamily(
    configFontFamilyNormal: String,
    configFontFamilyMedium: String,
): FontFamily {
    val fontFamilyNormal = getAndroidConfig(configFontFamilyNormal)
    val fontFamilyMedium = getAndroidConfig(configFontFamilyMedium)
    if (fontFamilyNormal.isEmpty() || fontFamilyMedium.isEmpty()) return FontFamily.Default
    return FontFamily(
        Font(DeviceFontFamilyName(fontFamilyNormal), FontWeight.Normal),
        Font(DeviceFontFamilyName(fontFamilyMedium), FontWeight.Medium),
    )
}

private fun Context.getAndroidConfig(configName: String): String {
    @SuppressLint("DiscouragedApi")
    val configId = resources.getIdentifier(configName, "string", "android")
    return resources.getString(configId)
}

@Composable
internal fun rememberSettingsFontFamily(): SettingsFontFamily {
    return rememberContext(Context::getSettingsFontFamily)
}

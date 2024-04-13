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
package com.android.credentialmanager.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.wear.compose.material.Colors
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.wear.compose.material.Typography
import androidx.wear.compose.material.MaterialTheme
import com.android.credentialmanager.R
import androidx.compose.ui.graphics.Color

/** The Material 3 Theme Wrapper for Supporting RRO. */
@Composable
fun WearCredentialSelectorTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            overlayColors(context)
                .copy(error = MaterialTheme.colors.error, onError = MaterialTheme.colors.onError)
        } else {
            MaterialTheme.colors
        }
    MaterialTheme(colors = colors, typography = deviceDefaultTypography(context), content = content)
}

/**
 * Creates a dynamic color maps that can be overlaid. 100 - Lightest shade; 0 - Darkest Shade; In
 * wear we only support dark theme for the time being. Thus the fill colors and variants are dark
 * and anything on top is light. We will use this custom redirection until wear compose material
 * supports color scheming.
 *
 * The mapping is best case match on wear material color tokens from
 * /android/clockwork/common/wearable/wearmaterial/color/res/values/color-tokens.xml
 *
 * @param context The context required to get system resource data.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal fun overlayColors(context: Context): Colors {
    val tonalPalette = dynamicTonalPalette(context)
    return Colors(
        background = Color.Black,
        onBackground = Color.White,
        primary = tonalPalette.primary90,
        primaryVariant = tonalPalette.primary80,
        onPrimary = tonalPalette.primary10,
        secondary = tonalPalette.tertiary90,
        secondaryVariant = tonalPalette.tertiary60,
        onSecondary = tonalPalette.tertiary10,
        surface = tonalPalette.neutral20,
        onSurface = tonalPalette.neutral95,
        onSurfaceVariant = tonalPalette.neutralVariant80,
    )
}

private fun fontFamily(context: Context, @StringRes id: Int): FontFamily {
    val typefaceName = context.resources.getString(id)
    val font = Font(familyName = DeviceFontFamilyName(typefaceName))
    return FontFamily(font)
}

/*
 Only customizes font family. The material 3 roles to 2.5 are mapped to the best case matching of
 google3/java/com/google/android/wearable/libraries/compose/theme/GoogleMaterialTheme.kt
*/
internal fun deviceDefaultTypography(context: Context): Typography {
    val defaultTypography = Typography()
    return Typography(
        display1 =
        defaultTypography.display1.copy(
            fontFamily =
            fontFamily(context, R.string.wear_material_compose_display_1_font_family)
        ),
        display2 =
        defaultTypography.display2.copy(
            fontFamily =
            fontFamily(context, R.string.wear_material_compose_display_2_font_family)
        ),
        display3 =
        defaultTypography.display1.copy(
            fontFamily =
            fontFamily(context, R.string.wear_material_compose_display_3_font_family)
        ),
        title1 =
        defaultTypography.title1.copy(
            fontFamily = fontFamily(context, R.string.wear_material_compose_title_1_font_family)
        ),
        title2 =
        defaultTypography.title2.copy(
            fontFamily = fontFamily(context, R.string.wear_material_compose_title_2_font_family)
        ),
        title3 =
        defaultTypography.title3.copy(
            fontFamily = fontFamily(context, R.string.wear_material_compose_title_3_font_family)
        ),
        body1 =
        defaultTypography.body1.copy(
            fontFamily = fontFamily(context, R.string.wear_material_compose_body_1_font_family)
        ),
        body2 =
        defaultTypography.body2.copy(
            fontFamily = fontFamily(context, R.string.wear_material_compose_body_2_font_family)
        ),
        button =
        defaultTypography.button.copy(
            fontFamily = fontFamily(context, R.string.wear_material_compose_button_font_family)
        ),
        caption1 =
        defaultTypography.caption1.copy(
            fontFamily =
            fontFamily(context, R.string.wear_material_compose_caption_1_font_family)
        ),
        caption2 =
        defaultTypography.caption2.copy(
            fontFamily =
            fontFamily(context, R.string.wear_material_compose_caption_2_font_family)
        ),
        caption3 =
        defaultTypography.caption3.copy(
            fontFamily =
            fontFamily(context, R.string.wear_material_compose_caption_3_font_family)
        ),
    )
}
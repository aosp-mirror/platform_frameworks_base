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

import android.R
import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.DoNotInline
import androidx.compose.ui.graphics.Color

/**
 * Tonal Palette structure in Material.
 *
 * A tonal palette is comprised of 5 tonal ranges. Each tonal range includes the 13 stops, or
 * tonal swatches.
 *
 * Tonal range names are:
 * - Neutral (N)
 * - Neutral variant (NV)
 * - Primary (P)
 * - Secondary (S)
 * - Tertiary (T)
 */
internal class SettingsTonalPalette(
    // The neutral tonal range from the generated dynamic color palette.
    // Ordered from the lightest shade [neutral100] to the darkest shade [neutral0].
    val neutral100: Color,
    val neutral99: Color,
    val neutral95: Color,
    val neutral90: Color,
    val neutral80: Color,
    val neutral70: Color,
    val neutral60: Color,
    val neutral50: Color,
    val neutral40: Color,
    val neutral30: Color,
    val neutral20: Color,
    val neutral10: Color,
    val neutral0: Color,

    // The neutral variant tonal range, sometimes called "neutral 2",  from the
    // generated dynamic color palette.
    // Ordered from the lightest shade [neutralVariant100] to the darkest shade [neutralVariant0].
    val neutralVariant100: Color,
    val neutralVariant99: Color,
    val neutralVariant95: Color,
    val neutralVariant90: Color,
    val neutralVariant80: Color,
    val neutralVariant70: Color,
    val neutralVariant60: Color,
    val neutralVariant50: Color,
    val neutralVariant40: Color,
    val neutralVariant30: Color,
    val neutralVariant20: Color,
    val neutralVariant10: Color,
    val neutralVariant0: Color,

    // The primary tonal range from the generated dynamic color palette.
    // Ordered from the lightest shade [primary100] to the darkest shade [primary0].
    val primary100: Color,
    val primary99: Color,
    val primary95: Color,
    val primary90: Color,
    val primary80: Color,
    val primary70: Color,
    val primary60: Color,
    val primary50: Color,
    val primary40: Color,
    val primary30: Color,
    val primary20: Color,
    val primary10: Color,
    val primary0: Color,

    // The secondary tonal range from the generated dynamic color palette.
    // Ordered from the lightest shade [secondary100] to the darkest shade [secondary0].
    val secondary100: Color,
    val secondary99: Color,
    val secondary95: Color,
    val secondary90: Color,
    val secondary80: Color,
    val secondary70: Color,
    val secondary60: Color,
    val secondary50: Color,
    val secondary40: Color,
    val secondary30: Color,
    val secondary20: Color,
    val secondary10: Color,
    val secondary0: Color,

    // The tertiary tonal range from the generated dynamic color palette.
    // Ordered from the lightest shade [tertiary100] to the darkest shade [tertiary0].
    val tertiary100: Color,
    val tertiary99: Color,
    val tertiary95: Color,
    val tertiary90: Color,
    val tertiary80: Color,
    val tertiary70: Color,
    val tertiary60: Color,
    val tertiary50: Color,
    val tertiary40: Color,
    val tertiary30: Color,
    val tertiary20: Color,
    val tertiary10: Color,
    val tertiary0: Color,
)

/** Static colors in Material. */
internal fun tonalPalette() = SettingsTonalPalette(
    // The neutral static tonal range.
    neutral100 = Color(red = 255, green = 255, blue = 255),
    neutral99 = Color(red = 255, green = 251, blue = 254),
    neutral95 = Color(red = 244, green = 239, blue = 244),
    neutral90 = Color(red = 230, green = 225, blue = 229),
    neutral80 = Color(red = 201, green = 197, blue = 202),
    neutral70 = Color(red = 174, green = 170, blue = 174),
    neutral60 = Color(red = 147, green = 144, blue = 148),
    neutral50 = Color(red = 120, green = 117, blue = 121),
    neutral40 = Color(red = 96, green = 93, blue = 98),
    neutral30 = Color(red = 72, green = 70, blue = 73),
    neutral20 = Color(red = 49, green = 48, blue = 51),
    neutral10 = Color(red = 28, green = 27, blue = 31),
    neutral0 = Color(red = 0, green = 0, blue = 0),

    // The neutral variant static tonal range, sometimes called "neutral 2".
    neutralVariant100 = Color(red = 255, green = 255, blue = 255),
    neutralVariant99 = Color(red = 255, green = 251, blue = 254),
    neutralVariant95 = Color(red = 245, green = 238, blue = 250),
    neutralVariant90 = Color(red = 231, green = 224, blue = 236),
    neutralVariant80 = Color(red = 202, green = 196, blue = 208),
    neutralVariant70 = Color(red = 174, green = 169, blue = 180),
    neutralVariant60 = Color(red = 147, green = 143, blue = 153),
    neutralVariant50 = Color(red = 121, green = 116, blue = 126),
    neutralVariant40 = Color(red = 96, green = 93, blue = 102),
    neutralVariant30 = Color(red = 73, green = 69, blue = 79),
    neutralVariant20 = Color(red = 50, green = 47, blue = 55),
    neutralVariant10 = Color(red = 29, green = 26, blue = 34),
    neutralVariant0 = Color(red = 0, green = 0, blue = 0),

    // The primary static tonal range.
    primary100 = Color(red = 255, green = 255, blue = 255),
    primary99 = Color(red = 255, green = 251, blue = 254),
    primary95 = Color(red = 246, green = 237, blue = 255),
    primary90 = Color(red = 234, green = 221, blue = 255),
    primary80 = Color(red = 208, green = 188, blue = 255),
    primary70 = Color(red = 182, green = 157, blue = 248),
    primary60 = Color(red = 154, green = 130, blue = 219),
    primary50 = Color(red = 127, green = 103, blue = 190),
    primary40 = Color(red = 103, green = 80, blue = 164),
    primary30 = Color(red = 79, green = 55, blue = 139),
    primary20 = Color(red = 56, green = 30, blue = 114),
    primary10 = Color(red = 33, green = 0, blue = 93),
    primary0 = Color(red = 33, green = 0, blue = 93),

    // The secondary static tonal range.
    secondary100 = Color(red = 255, green = 255, blue = 255),
    secondary99 = Color(red = 255, green = 251, blue = 254),
    secondary95 = Color(red = 246, green = 237, blue = 255),
    secondary90 = Color(red = 232, green = 222, blue = 248),
    secondary80 = Color(red = 204, green = 194, blue = 220),
    secondary70 = Color(red = 176, green = 167, blue = 192),
    secondary60 = Color(red = 149, green = 141, blue = 165),
    secondary50 = Color(red = 122, green = 114, blue = 137),
    secondary40 = Color(red = 98, green = 91, blue = 113),
    secondary30 = Color(red = 74, green = 68, blue = 88),
    secondary20 = Color(red = 51, green = 45, blue = 65),
    secondary10 = Color(red = 29, green = 25, blue = 43),
    secondary0 = Color(red = 0, green = 0, blue = 0),

    // The tertiary static tonal range.
    tertiary100 = Color(red = 255, green = 255, blue = 255),
    tertiary99 = Color(red = 255, green = 251, blue = 250),
    tertiary95 = Color(red = 255, green = 236, blue = 241),
    tertiary90 = Color(red = 255, green = 216, blue = 228),
    tertiary80 = Color(red = 239, green = 184, blue = 200),
    tertiary70 = Color(red = 210, green = 157, blue = 172),
    tertiary60 = Color(red = 181, green = 131, blue = 146),
    tertiary50 = Color(red = 152, green = 105, blue = 119),
    tertiary40 = Color(red = 125, green = 82, blue = 96),
    tertiary30 = Color(red = 99, green = 59, blue = 72),
    tertiary20 = Color(red = 73, green = 37, blue = 50),
    tertiary10 = Color(red = 49, green = 17, blue = 29),
    tertiary0 = Color(red = 0, green = 0, blue = 0),
)

/** Dynamic colors in Material. */
internal fun dynamicTonalPalette(context: Context) = SettingsTonalPalette(
    // The neutral tonal range from the generated dynamic color palette.
    neutral100 = ColorResourceHelper.getColor(context, R.color.system_neutral1_0),
    neutral99 = ColorResourceHelper.getColor(context, R.color.system_neutral1_10),
    neutral95 = ColorResourceHelper.getColor(context, R.color.system_neutral1_50),
    neutral90 = ColorResourceHelper.getColor(context, R.color.system_neutral1_100),
    neutral80 = ColorResourceHelper.getColor(context, R.color.system_neutral1_200),
    neutral70 = ColorResourceHelper.getColor(context, R.color.system_neutral1_300),
    neutral60 = ColorResourceHelper.getColor(context, R.color.system_neutral1_400),
    neutral50 = ColorResourceHelper.getColor(context, R.color.system_neutral1_500),
    neutral40 = ColorResourceHelper.getColor(context, R.color.system_neutral1_600),
    neutral30 = ColorResourceHelper.getColor(context, R.color.system_neutral1_700),
    neutral20 = ColorResourceHelper.getColor(context, R.color.system_neutral1_800),
    neutral10 = ColorResourceHelper.getColor(context, R.color.system_neutral1_900),
    neutral0 = ColorResourceHelper.getColor(context, R.color.system_neutral1_1000),

    // The neutral variant tonal range, sometimes called "neutral 2",  from the
    // generated dynamic color palette.
    neutralVariant100 = ColorResourceHelper.getColor(context, R.color.system_neutral2_0),
    neutralVariant99 = ColorResourceHelper.getColor(context, R.color.system_neutral2_10),
    neutralVariant95 = ColorResourceHelper.getColor(context, R.color.system_neutral2_50),
    neutralVariant90 = ColorResourceHelper.getColor(context, R.color.system_neutral2_100),
    neutralVariant80 = ColorResourceHelper.getColor(context, R.color.system_neutral2_200),
    neutralVariant70 = ColorResourceHelper.getColor(context, R.color.system_neutral2_300),
    neutralVariant60 = ColorResourceHelper.getColor(context, R.color.system_neutral2_400),
    neutralVariant50 = ColorResourceHelper.getColor(context, R.color.system_neutral2_500),
    neutralVariant40 = ColorResourceHelper.getColor(context, R.color.system_neutral2_600),
    neutralVariant30 = ColorResourceHelper.getColor(context, R.color.system_neutral2_700),
    neutralVariant20 = ColorResourceHelper.getColor(context, R.color.system_neutral2_800),
    neutralVariant10 = ColorResourceHelper.getColor(context, R.color.system_neutral2_900),
    neutralVariant0 = ColorResourceHelper.getColor(context, R.color.system_neutral2_1000),

    // The primary tonal range from the generated dynamic color palette.
    primary100 = ColorResourceHelper.getColor(context, R.color.system_accent1_0),
    primary99 = ColorResourceHelper.getColor(context, R.color.system_accent1_10),
    primary95 = ColorResourceHelper.getColor(context, R.color.system_accent1_50),
    primary90 = ColorResourceHelper.getColor(context, R.color.system_accent1_100),
    primary80 = ColorResourceHelper.getColor(context, R.color.system_accent1_200),
    primary70 = ColorResourceHelper.getColor(context, R.color.system_accent1_300),
    primary60 = ColorResourceHelper.getColor(context, R.color.system_accent1_400),
    primary50 = ColorResourceHelper.getColor(context, R.color.system_accent1_500),
    primary40 = ColorResourceHelper.getColor(context, R.color.system_accent1_600),
    primary30 = ColorResourceHelper.getColor(context, R.color.system_accent1_700),
    primary20 = ColorResourceHelper.getColor(context, R.color.system_accent1_800),
    primary10 = ColorResourceHelper.getColor(context, R.color.system_accent1_900),
    primary0 = ColorResourceHelper.getColor(context, R.color.system_accent1_1000),

    // The secondary tonal range from the generated dynamic color palette.
    secondary100 = ColorResourceHelper.getColor(context, R.color.system_accent2_0),
    secondary99 = ColorResourceHelper.getColor(context, R.color.system_accent2_10),
    secondary95 = ColorResourceHelper.getColor(context, R.color.system_accent2_50),
    secondary90 = ColorResourceHelper.getColor(context, R.color.system_accent2_100),
    secondary80 = ColorResourceHelper.getColor(context, R.color.system_accent2_200),
    secondary70 = ColorResourceHelper.getColor(context, R.color.system_accent2_300),
    secondary60 = ColorResourceHelper.getColor(context, R.color.system_accent2_400),
    secondary50 = ColorResourceHelper.getColor(context, R.color.system_accent2_500),
    secondary40 = ColorResourceHelper.getColor(context, R.color.system_accent2_600),
    secondary30 = ColorResourceHelper.getColor(context, R.color.system_accent2_700),
    secondary20 = ColorResourceHelper.getColor(context, R.color.system_accent2_800),
    secondary10 = ColorResourceHelper.getColor(context, R.color.system_accent2_900),
    secondary0 = ColorResourceHelper.getColor(context, R.color.system_accent2_1000),

    // The tertiary tonal range from the generated dynamic color palette.
    tertiary100 = ColorResourceHelper.getColor(context, R.color.system_accent3_0),
    tertiary99 = ColorResourceHelper.getColor(context, R.color.system_accent3_10),
    tertiary95 = ColorResourceHelper.getColor(context, R.color.system_accent3_50),
    tertiary90 = ColorResourceHelper.getColor(context, R.color.system_accent3_100),
    tertiary80 = ColorResourceHelper.getColor(context, R.color.system_accent3_200),
    tertiary70 = ColorResourceHelper.getColor(context, R.color.system_accent3_300),
    tertiary60 = ColorResourceHelper.getColor(context, R.color.system_accent3_400),
    tertiary50 = ColorResourceHelper.getColor(context, R.color.system_accent3_500),
    tertiary40 = ColorResourceHelper.getColor(context, R.color.system_accent3_600),
    tertiary30 = ColorResourceHelper.getColor(context, R.color.system_accent3_700),
    tertiary20 = ColorResourceHelper.getColor(context, R.color.system_accent3_800),
    tertiary10 = ColorResourceHelper.getColor(context, R.color.system_accent3_900),
    tertiary0 = ColorResourceHelper.getColor(context, R.color.system_accent3_1000),
)

private object ColorResourceHelper {
    @DoNotInline
    fun getColor(context: Context, @ColorRes id: Int): Color {
        return Color(context.resources.getColor(id, context.theme))
    }
}

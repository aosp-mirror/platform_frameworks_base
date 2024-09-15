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

import android.R
import android.content.Context
import android.os.Build
import androidx.annotation.ColorRes
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi

import androidx.compose.ui.graphics.Color

/**
 * Tonal Palette structure in Material.
 *
 * A tonal palette is comprised of 5 tonal ranges. Each tonal range includes the 13 stops, or tonal
 * swatches.
 *
 * Tonal range names are:
 * - Neutral (N)
 * - Neutral variant (NV)
 * - Primary (P)
 * - Secondary (S)
 * - Tertiary (T)
 */
internal class WearCredentialSelectorTonalPalette(
    // The neutral tonal range.
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

    // The neutral variant tonal range, sometimes called "neutral 2"
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

    // The primary tonal range, also known as accent 1
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

    // The Secondary tonal range, also know as accent 2
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

    // The tertiary tonal range, also known as accent 3
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
/** Dynamic colors for wear compose material to support resource overlay. */
@RequiresApi(Build.VERSION_CODES.S)
// TODO: once we have proper support for this on Wear 6+, we will do something similar to
// https://source.corp.google.com/h/android/platform/superproject/+/androidx-main:frameworks/support/compose/material3/material3/src/androidMain/kotlin/androidx/compose/material3/DynamicTonalPalette.android.kt;l=307-362?q=dynamicTonalPalette&sq=repo:android%2Fplatform%2Fsuperproject%20b:androidx-main
// Tracking Bug: b/270720571
internal fun dynamicTonalPalette(context: Context) =
    WearCredentialSelectorTonalPalette(
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
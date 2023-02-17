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

package com.android.compose.theme.typography

import androidx.compose.ui.text.TextStyle

internal class TypographyTokens(typeScaleTokens: TypeScaleTokens) {
    val bodyLarge =
        TextStyle(
            fontFamily = typeScaleTokens.bodyLargeFont,
            fontWeight = typeScaleTokens.bodyLargeWeight,
            fontSize = typeScaleTokens.bodyLargeSize,
            lineHeight = typeScaleTokens.bodyLargeLineHeight,
            letterSpacing = typeScaleTokens.bodyLargeTracking,
        )
    val bodyMedium =
        TextStyle(
            fontFamily = typeScaleTokens.bodyMediumFont,
            fontWeight = typeScaleTokens.bodyMediumWeight,
            fontSize = typeScaleTokens.bodyMediumSize,
            lineHeight = typeScaleTokens.bodyMediumLineHeight,
            letterSpacing = typeScaleTokens.bodyMediumTracking,
        )
    val bodySmall =
        TextStyle(
            fontFamily = typeScaleTokens.bodySmallFont,
            fontWeight = typeScaleTokens.bodySmallWeight,
            fontSize = typeScaleTokens.bodySmallSize,
            lineHeight = typeScaleTokens.bodySmallLineHeight,
            letterSpacing = typeScaleTokens.bodySmallTracking,
        )
    val displayLarge =
        TextStyle(
            fontFamily = typeScaleTokens.displayLargeFont,
            fontWeight = typeScaleTokens.displayLargeWeight,
            fontSize = typeScaleTokens.displayLargeSize,
            lineHeight = typeScaleTokens.displayLargeLineHeight,
            letterSpacing = typeScaleTokens.displayLargeTracking,
        )
    val displayMedium =
        TextStyle(
            fontFamily = typeScaleTokens.displayMediumFont,
            fontWeight = typeScaleTokens.displayMediumWeight,
            fontSize = typeScaleTokens.displayMediumSize,
            lineHeight = typeScaleTokens.displayMediumLineHeight,
            letterSpacing = typeScaleTokens.displayMediumTracking,
        )
    val displaySmall =
        TextStyle(
            fontFamily = typeScaleTokens.displaySmallFont,
            fontWeight = typeScaleTokens.displaySmallWeight,
            fontSize = typeScaleTokens.displaySmallSize,
            lineHeight = typeScaleTokens.displaySmallLineHeight,
            letterSpacing = typeScaleTokens.displaySmallTracking,
        )
    val headlineLarge =
        TextStyle(
            fontFamily = typeScaleTokens.headlineLargeFont,
            fontWeight = typeScaleTokens.headlineLargeWeight,
            fontSize = typeScaleTokens.headlineLargeSize,
            lineHeight = typeScaleTokens.headlineLargeLineHeight,
            letterSpacing = typeScaleTokens.headlineLargeTracking,
        )
    val headlineMedium =
        TextStyle(
            fontFamily = typeScaleTokens.headlineMediumFont,
            fontWeight = typeScaleTokens.headlineMediumWeight,
            fontSize = typeScaleTokens.headlineMediumSize,
            lineHeight = typeScaleTokens.headlineMediumLineHeight,
            letterSpacing = typeScaleTokens.headlineMediumTracking,
        )
    val headlineSmall =
        TextStyle(
            fontFamily = typeScaleTokens.headlineSmallFont,
            fontWeight = typeScaleTokens.headlineSmallWeight,
            fontSize = typeScaleTokens.headlineSmallSize,
            lineHeight = typeScaleTokens.headlineSmallLineHeight,
            letterSpacing = typeScaleTokens.headlineSmallTracking,
        )
    val labelLarge =
        TextStyle(
            fontFamily = typeScaleTokens.labelLargeFont,
            fontWeight = typeScaleTokens.labelLargeWeight,
            fontSize = typeScaleTokens.labelLargeSize,
            lineHeight = typeScaleTokens.labelLargeLineHeight,
            letterSpacing = typeScaleTokens.labelLargeTracking,
        )
    val labelMedium =
        TextStyle(
            fontFamily = typeScaleTokens.labelMediumFont,
            fontWeight = typeScaleTokens.labelMediumWeight,
            fontSize = typeScaleTokens.labelMediumSize,
            lineHeight = typeScaleTokens.labelMediumLineHeight,
            letterSpacing = typeScaleTokens.labelMediumTracking,
        )
    val labelSmall =
        TextStyle(
            fontFamily = typeScaleTokens.labelSmallFont,
            fontWeight = typeScaleTokens.labelSmallWeight,
            fontSize = typeScaleTokens.labelSmallSize,
            lineHeight = typeScaleTokens.labelSmallLineHeight,
            letterSpacing = typeScaleTokens.labelSmallTracking,
        )
    val titleLarge =
        TextStyle(
            fontFamily = typeScaleTokens.titleLargeFont,
            fontWeight = typeScaleTokens.titleLargeWeight,
            fontSize = typeScaleTokens.titleLargeSize,
            lineHeight = typeScaleTokens.titleLargeLineHeight,
            letterSpacing = typeScaleTokens.titleLargeTracking,
        )
    val titleMedium =
        TextStyle(
            fontFamily = typeScaleTokens.titleMediumFont,
            fontWeight = typeScaleTokens.titleMediumWeight,
            fontSize = typeScaleTokens.titleMediumSize,
            lineHeight = typeScaleTokens.titleMediumLineHeight,
            letterSpacing = typeScaleTokens.titleMediumTracking,
        )
    val titleSmall =
        TextStyle(
            fontFamily = typeScaleTokens.titleSmallFont,
            fontWeight = typeScaleTokens.titleSmallWeight,
            fontSize = typeScaleTokens.titleSmallSize,
            lineHeight = typeScaleTokens.titleSmallLineHeight,
            letterSpacing = typeScaleTokens.titleSmallTracking,
        )
}

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

package com.android.systemui.compose.theme.typography

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography

/**
 * The SystemUI typography.
 *
 * Do not use directly and call [MaterialTheme.typography] instead to access the different text
 * styles.
 */
internal val SystemUITypography =
    Typography(
        displayLarge = TypographyTokens.DisplayLarge,
        displayMedium = TypographyTokens.DisplayMedium,
        displaySmall = TypographyTokens.DisplaySmall,
        headlineLarge = TypographyTokens.HeadlineLarge,
        headlineMedium = TypographyTokens.HeadlineMedium,
        headlineSmall = TypographyTokens.HeadlineSmall,
        titleLarge = TypographyTokens.TitleLarge,
        titleMedium = TypographyTokens.TitleMedium,
        titleSmall = TypographyTokens.TitleSmall,
        bodyLarge = TypographyTokens.BodyLarge,
        bodyMedium = TypographyTokens.BodyMedium,
        bodySmall = TypographyTokens.BodySmall,
        labelLarge = TypographyTokens.LabelLarge,
        labelMedium = TypographyTokens.LabelMedium,
        labelSmall = TypographyTokens.LabelSmall,
    )

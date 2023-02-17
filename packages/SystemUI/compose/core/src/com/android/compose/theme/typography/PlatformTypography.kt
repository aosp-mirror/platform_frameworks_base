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

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography

/**
 * The typography for Platform Compose code.
 *
 * Do not use directly and call [MaterialTheme.typography] instead to access the different text
 * styles.
 */
internal fun platformTypography(typographyTokens: TypographyTokens): Typography {
    return Typography(
        displayLarge = typographyTokens.displayLarge,
        displayMedium = typographyTokens.displayMedium,
        displaySmall = typographyTokens.displaySmall,
        headlineLarge = typographyTokens.headlineLarge,
        headlineMedium = typographyTokens.headlineMedium,
        headlineSmall = typographyTokens.headlineSmall,
        titleLarge = typographyTokens.titleLarge,
        titleMedium = typographyTokens.titleMedium,
        titleSmall = typographyTokens.titleSmall,
        bodyLarge = typographyTokens.bodyLarge,
        bodyMedium = typographyTokens.bodyMedium,
        bodySmall = typographyTokens.bodySmall,
        labelLarge = typographyTokens.labelLarge,
        labelMedium = typographyTokens.labelMedium,
        labelSmall = typographyTokens.labelSmall,
    )
}

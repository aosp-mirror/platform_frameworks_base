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

package com.android.compose.theme

import android.annotation.ColorRes
import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.internal.R
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformThemeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun testThemeShowsContent() {
        composeRule.setContent { PlatformTheme { Text("foo") } }

        composeRule.onNodeWithText("foo").assertIsDisplayed()
    }

    @Test
    fun testAndroidColorsAreAvailableInsideTheme() {
        composeRule.setContent {
            PlatformTheme { Text("foo", color = LocalAndroidColorScheme.current.primaryFixed) }
        }

        composeRule.onNodeWithText("foo").assertIsDisplayed()
    }

    @Test
    fun testAccessingAndroidColorsWithoutThemeThrows() {
        assertThrows(IllegalStateException::class.java) {
            composeRule.setContent {
                Text("foo", color = LocalAndroidColorScheme.current.primaryFixed)
            }
        }
    }

    @Test
    fun testMaterialColorsMatchAttributeValue() {
        val colorValues = mutableListOf<ColorValue>()

        fun onLaunch(colorScheme: ColorScheme, context: Context) {
            fun addValue(name: String, materialValue: Color, @ColorRes color: Int) {
                colorValues.add(ColorValue(name, materialValue, Color(context.getColor(color))))
            }

            addValue("primary", colorScheme.primary, R.color.materialColorPrimary)
            addValue("onPrimary", colorScheme.onPrimary, R.color.materialColorOnPrimary)
            addValue(
                "primaryContainer",
                colorScheme.primaryContainer,
                R.color.materialColorPrimaryContainer,
            )
            addValue(
                "onPrimaryContainer",
                colorScheme.onPrimaryContainer,
                R.color.materialColorOnPrimaryContainer,
            )
            addValue(
                "inversePrimary",
                colorScheme.inversePrimary,
                R.color.materialColorInversePrimary,
            )
            addValue("secondary", colorScheme.secondary, R.color.materialColorSecondary)
            addValue("onSecondary", colorScheme.onSecondary, R.color.materialColorOnSecondary)
            addValue(
                "secondaryContainer",
                colorScheme.secondaryContainer,
                R.color.materialColorSecondaryContainer,
            )
            addValue(
                "onSecondaryContainer",
                colorScheme.onSecondaryContainer,
                R.color.materialColorOnSecondaryContainer,
            )
            addValue("tertiary", colorScheme.tertiary, R.color.materialColorTertiary)
            addValue("onTertiary", colorScheme.onTertiary, R.color.materialColorOnTertiary)
            addValue(
                "tertiaryContainer",
                colorScheme.tertiaryContainer,
                R.color.materialColorTertiaryContainer,
            )
            addValue(
                "onTertiaryContainer",
                colorScheme.onTertiaryContainer,
                R.color.materialColorOnTertiaryContainer,
            )
            addValue("onBackground", colorScheme.onBackground, R.color.materialColorOnBackground)
            addValue("surface", colorScheme.surface, R.color.materialColorSurface)
            addValue("onSurface", colorScheme.onSurface, R.color.materialColorOnSurface)
            addValue(
                "surfaceVariant",
                colorScheme.surfaceVariant,
                R.color.materialColorSurfaceVariant,
            )
            addValue(
                "onSurfaceVariant",
                colorScheme.onSurfaceVariant,
                R.color.materialColorOnSurfaceVariant,
            )
            addValue(
                "inverseSurface",
                colorScheme.inverseSurface,
                R.color.materialColorInverseSurface,
            )
            addValue(
                "inverseOnSurface",
                colorScheme.inverseOnSurface,
                R.color.materialColorInverseOnSurface,
            )
            addValue("error", colorScheme.error, R.color.materialColorError)
            addValue("onError", colorScheme.onError, R.color.materialColorOnError)
            addValue(
                "errorContainer",
                colorScheme.errorContainer,
                R.color.materialColorErrorContainer,
            )
            addValue(
                "onErrorContainer",
                colorScheme.onErrorContainer,
                R.color.materialColorOnErrorContainer,
            )
            addValue("outline", colorScheme.outline, R.color.materialColorOutline)
            addValue(
                "outlineVariant",
                colorScheme.outlineVariant,
                R.color.materialColorOutlineVariant,
            )
            addValue("surfaceBright", colorScheme.surfaceBright, R.color.materialColorSurfaceBright)
            addValue("surfaceDim", colorScheme.surfaceDim, R.color.materialColorSurfaceDim)
            addValue(
                "surfaceContainer",
                colorScheme.surfaceContainer,
                R.color.materialColorSurfaceContainer,
            )
            addValue(
                "surfaceContainerHigh",
                colorScheme.surfaceContainerHigh,
                R.color.materialColorSurfaceContainerHigh,
            )
            addValue(
                "surfaceContainerHighest",
                colorScheme.surfaceContainerHighest,
                R.color.materialColorSurfaceContainerHighest,
            )
            addValue(
                "surfaceContainerLow",
                colorScheme.surfaceContainerLow,
                R.color.materialColorSurfaceContainerLow,
            )
            addValue(
                "surfaceContainerLowest",
                colorScheme.surfaceContainerLowest,
                R.color.materialColorSurfaceContainerLowest,
            )
        }

        composeRule.setContent {
            PlatformTheme {
                val colorScheme = MaterialTheme.colorScheme
                val context = LocalContext.current

                LaunchedEffect(Unit) { onLaunch(colorScheme, context) }
            }
        }

        assertThat(colorValues).hasSize(33)
        colorValues.forEach { colorValue ->
            assertWithMessage(
                    "MaterialTheme.colorScheme.${colorValue.name} matches attribute color"
                )
                .that(colorValue.materialValue)
                .isEqualTo(colorValue.colorValue)
        }
    }

    private data class ColorValue(val name: String, val materialValue: Color, val colorValue: Color)
}

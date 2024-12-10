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

import android.content.Context
import androidx.annotation.AttrRes
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
            fun addValue(name: String, materialValue: Color, @AttrRes attr: Int) {
                colorValues.add(ColorValue(name, materialValue, colorAttr(context, attr)))
            }

            addValue("primary", colorScheme.primary, R.attr.materialColorPrimary)
            addValue("onPrimary", colorScheme.onPrimary, R.attr.materialColorOnPrimary)
            addValue(
                "primaryContainer",
                colorScheme.primaryContainer,
                R.attr.materialColorPrimaryContainer,
            )
            addValue(
                "onPrimaryContainer",
                colorScheme.onPrimaryContainer,
                R.attr.materialColorOnPrimaryContainer,
            )
            addValue(
                "inversePrimary",
                colorScheme.inversePrimary,
                R.attr.materialColorInversePrimary,
            )
            addValue("secondary", colorScheme.secondary, R.attr.materialColorSecondary)
            addValue("onSecondary", colorScheme.onSecondary, R.attr.materialColorOnSecondary)
            addValue(
                "secondaryContainer",
                colorScheme.secondaryContainer,
                R.attr.materialColorSecondaryContainer,
            )
            addValue(
                "onSecondaryContainer",
                colorScheme.onSecondaryContainer,
                R.attr.materialColorOnSecondaryContainer,
            )
            addValue("tertiary", colorScheme.tertiary, R.attr.materialColorTertiary)
            addValue("onTertiary", colorScheme.onTertiary, R.attr.materialColorOnTertiary)
            addValue(
                "tertiaryContainer",
                colorScheme.tertiaryContainer,
                R.attr.materialColorTertiaryContainer,
            )
            addValue(
                "onTertiaryContainer",
                colorScheme.onTertiaryContainer,
                R.attr.materialColorOnTertiaryContainer,
            )
            addValue("onBackground", colorScheme.onBackground, R.attr.materialColorOnBackground)
            addValue("surface", colorScheme.surface, R.attr.materialColorSurface)
            addValue("onSurface", colorScheme.onSurface, R.attr.materialColorOnSurface)
            addValue(
                "surfaceVariant",
                colorScheme.surfaceVariant,
                R.attr.materialColorSurfaceVariant,
            )
            addValue(
                "onSurfaceVariant",
                colorScheme.onSurfaceVariant,
                R.attr.materialColorOnSurfaceVariant,
            )
            addValue(
                "inverseSurface",
                colorScheme.inverseSurface,
                R.attr.materialColorInverseSurface,
            )
            addValue(
                "inverseOnSurface",
                colorScheme.inverseOnSurface,
                R.attr.materialColorInverseOnSurface,
            )
            addValue("error", colorScheme.error, R.attr.materialColorError)
            addValue("onError", colorScheme.onError, R.attr.materialColorOnError)
            addValue(
                "errorContainer",
                colorScheme.errorContainer,
                R.attr.materialColorErrorContainer,
            )
            addValue(
                "onErrorContainer",
                colorScheme.onErrorContainer,
                R.attr.materialColorOnErrorContainer,
            )
            addValue("outline", colorScheme.outline, R.attr.materialColorOutline)
            addValue(
                "outlineVariant",
                colorScheme.outlineVariant,
                R.attr.materialColorOutlineVariant,
            )
            addValue("surfaceBright", colorScheme.surfaceBright, R.attr.materialColorSurfaceBright)
            addValue("surfaceDim", colorScheme.surfaceDim, R.attr.materialColorSurfaceDim)
            addValue(
                "surfaceContainer",
                colorScheme.surfaceContainer,
                R.attr.materialColorSurfaceContainer,
            )
            addValue(
                "surfaceContainerHigh",
                colorScheme.surfaceContainerHigh,
                R.attr.materialColorSurfaceContainerHigh,
            )
            addValue(
                "surfaceContainerHighest",
                colorScheme.surfaceContainerHighest,
                R.attr.materialColorSurfaceContainerHighest,
            )
            addValue(
                "surfaceContainerLow",
                colorScheme.surfaceContainerLow,
                R.attr.materialColorSurfaceContainerLow,
            )
            addValue(
                "surfaceContainerLowest",
                colorScheme.surfaceContainerLowest,
                R.attr.materialColorSurfaceContainerLowest,
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
                .isEqualTo(colorValue.attrValue)
        }
    }

    private data class ColorValue(val name: String, val materialValue: Color, val attrValue: Color)
}

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

@file:OptIn(ExperimentalLayoutApi::class)

package com.android.systemui.shade.ui.composable

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.waterfall
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.LowestZIndexContentPicker
import com.android.compose.animation.scene.SceneScope
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.systemui.scene.shared.model.Scenes

/** Renders a lightweight shade UI container, as an overlay. */
@Composable
fun SceneScope.OverlayShade(
    onScrimClicked: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    LaunchedEffect(Unit) {
        if (layoutState.currentTransition?.fromContent == Scenes.Gone) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
        }
    }

    Box(modifier) {
        Scrim(onClicked = onScrimClicked)

        Box(modifier = Modifier.fillMaxSize().panelPadding(), contentAlignment = Alignment.TopEnd) {
            Panel(
                modifier = Modifier.element(OverlayShade.Elements.Panel).panelSize(),
                content = content,
            )
        }
    }
}

@Composable
private fun SceneScope.Scrim(onClicked: () -> Unit, modifier: Modifier = Modifier) {
    Spacer(
        modifier =
            modifier
                .element(OverlayShade.Elements.Scrim)
                .fillMaxSize()
                .background(OverlayShade.Colors.ScrimBackground)
                .clickable(onClick = onClicked, interactionSource = null, indication = null)
    )
}

@Composable
private fun SceneScope.Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.clip(OverlayShade.Shapes.RoundedCornerPanel)) {
        Spacer(
            modifier =
                Modifier.element(OverlayShade.Elements.PanelBackground)
                    .matchParentSize()
                    .background(
                        color = OverlayShade.Colors.PanelBackground,
                        shape = OverlayShade.Shapes.RoundedCornerPanel,
                    )
        )

        // This content is intentionally rendered as a separate element from the background in order
        // to allow for more flexibility when defining transitions.
        content()
    }
}

@Composable
private fun Modifier.panelSize(): Modifier {
    val widthSizeClass = LocalWindowSizeClass.current.widthSizeClass

    return this.then(
        when (widthSizeClass) {
            WindowWidthSizeClass.Compact -> Modifier.fillMaxWidth()
            WindowWidthSizeClass.Medium -> Modifier.width(OverlayShade.Dimensions.PanelWidthMedium)
            WindowWidthSizeClass.Expanded -> Modifier.width(OverlayShade.Dimensions.PanelWidthLarge)
            else -> error("Unsupported WindowWidthSizeClass \"$widthSizeClass\"")
        }
    )
}

@Composable
private fun Modifier.panelPadding(): Modifier {
    val widthSizeClass = LocalWindowSizeClass.current.widthSizeClass
    val systemBars = WindowInsets.systemBarsIgnoringVisibility
    val displayCutout = WindowInsets.displayCutout
    val waterfall = WindowInsets.waterfall
    val contentPadding = PaddingValues(all = OverlayShade.Dimensions.ScrimContentPadding)

    val combinedPadding =
        combinePaddings(
            systemBars.asPaddingValues(),
            displayCutout.asPaddingValues(),
            waterfall.asPaddingValues(),
            contentPadding,
        )

    return if (widthSizeClass == WindowWidthSizeClass.Compact) {
        padding(bottom = combinedPadding.calculateBottomPadding())
    } else {
        padding(combinedPadding)
    }
}

/** Creates a union of [paddingValues] by using the max padding of each edge. */
@Composable
private fun combinePaddings(vararg paddingValues: PaddingValues): PaddingValues {
    return if (paddingValues.isEmpty()) {
        PaddingValues(0.dp)
    } else {
        val layoutDirection = LocalLayoutDirection.current
        PaddingValues(
            start = paddingValues.maxOf { it.calculateStartPadding(layoutDirection) },
            top = paddingValues.maxOf { it.calculateTopPadding() },
            end = paddingValues.maxOf { it.calculateEndPadding(layoutDirection) },
            bottom = paddingValues.maxOf { it.calculateBottomPadding() },
        )
    }
}

object OverlayShade {
    object Elements {
        val Scrim = ElementKey("OverlayShadeScrim", contentPicker = LowestZIndexContentPicker)
        val Panel =
            ElementKey(
                "OverlayShadePanel",
                contentPicker = LowestZIndexContentPicker,
                placeAllCopies = true,
            )
        val PanelBackground =
            ElementKey("OverlayShadePanelBackground", contentPicker = LowestZIndexContentPicker)
    }

    object Colors {
        val ScrimBackground = Color(0f, 0f, 0f, alpha = 0.2f)
        val PanelBackground: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer
    }

    object Dimensions {
        val ScrimContentPadding = 16.dp
        val PanelCornerRadius = 46.dp
        val PanelWidthMedium = 390.dp
        val PanelWidthLarge = 474.dp
        val OverscrollLimit = 32.dp
    }

    object Shapes {
        val RoundedCornerPanel = RoundedCornerShape(Dimensions.PanelCornerRadius)
    }
}

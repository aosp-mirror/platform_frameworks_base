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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.graphics.drawable.Animatable
import android.text.TextUtils
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.background
import com.android.compose.modifiers.thenIf
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabel
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.res.R
import kotlinx.coroutines.delay

private const val TEST_TAG_TOGGLE = "qs_tile_toggle_target"

@Composable
fun LargeTileContent(
    label: String,
    secondaryLabel: String?,
    icon: Icon,
    colors: TileColors,
    accessibilityUiState: AccessibilityUiState? = null,
    toggleClickSupported: Boolean = false,
    iconShape: Shape = RoundedCornerShape(CommonTileDefaults.InactiveCornerRadius),
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = tileHorizontalArrangement(),
    ) {
        // Icon
        val longPressLabel = longPressLabel()
        Box(
            modifier =
                Modifier.size(CommonTileDefaults.ToggleTargetSize).thenIf(toggleClickSupported) {
                    Modifier.clip(iconShape)
                        .background(colors.iconBackground, { 1f })
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = onLongClick,
                            onLongClickLabel = longPressLabel,
                        )
                        .thenIf(accessibilityUiState != null) {
                            Modifier.semantics {
                                    accessibilityUiState as AccessibilityUiState
                                    contentDescription = accessibilityUiState.contentDescription
                                    stateDescription = accessibilityUiState.stateDescription
                                    accessibilityUiState.toggleableState?.let {
                                        toggleableState = it
                                    }
                                    role = Role.Switch
                                }
                                .sysuiResTag(TEST_TAG_TOGGLE)
                        }
                }
        ) {
            SmallTileContent(
                icon = icon,
                color = colors.icon,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Labels
        LargeTileLabels(
            label = label,
            secondaryLabel = secondaryLabel,
            colors = colors,
            accessibilityUiState = accessibilityUiState,
        )
    }
}

@Composable
fun LargeTileLabels(
    label: String,
    secondaryLabel: String?,
    colors: TileColors,
    modifier: Modifier = Modifier,
    accessibilityUiState: AccessibilityUiState? = null,
) {
    Column(verticalArrangement = Arrangement.Center, modifier = modifier.fillMaxHeight()) {
        Text(label, color = colors.label, modifier = Modifier.tileMarquee())
        if (!TextUtils.isEmpty(secondaryLabel)) {
            Text(
                secondaryLabel ?: "",
                color = colors.secondaryLabel,
                modifier =
                    Modifier.tileMarquee().thenIf(
                        accessibilityUiState?.stateDescription?.contains(secondaryLabel ?: "") ==
                            true
                    ) {
                        Modifier.clearAndSetSemantics {}
                    },
            )
        }
    }
}

@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
fun SmallTileContent(
    modifier: Modifier = Modifier,
    icon: Icon,
    color: Color,
    animateToEnd: Boolean = false,
) {
    val iconModifier = modifier.size(CommonTileDefaults.IconSize)
    val context = LocalContext.current
    val loadedDrawable =
        remember(icon, context) {
            when (icon) {
                is Icon.Loaded -> icon.drawable
                is Icon.Resource -> context.getDrawable(icon.res)
            }
        }
    if (loadedDrawable !is Animatable) {
        Icon(icon = icon, tint = color, modifier = iconModifier)
    } else if (icon is Icon.Resource) {
        val image = AnimatedImageVector.animatedVectorResource(id = icon.res)
        val painter =
            if (animateToEnd) {
                rememberAnimatedVectorPainter(animatedImageVector = image, atEnd = true)
            } else {
                var atEnd by remember(icon.res) { mutableStateOf(false) }
                LaunchedEffect(key1 = icon.res) {
                    delay(350)
                    atEnd = true
                }
                rememberAnimatedVectorPainter(animatedImageVector = image, atEnd = atEnd)
            }
        Image(
            painter = painter,
            contentDescription = icon.contentDescription?.load(),
            colorFilter = ColorFilter.tint(color = color),
            modifier = iconModifier,
        )
    }
}

object CommonTileDefaults {
    val IconSize = 24.dp
    val ToggleTargetSize = 56.dp
    val TileHeight = 72.dp
    val TilePadding = 8.dp
    val TileArrangementPadding = 6.dp
    val InactiveCornerRadius = 50.dp

    @Composable fun longPressLabel() = stringResource(id = R.string.accessibility_long_click_tile)
}

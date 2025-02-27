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
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.size
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.Flags
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.SideIconHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.SideIconWidth
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabel
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R

private const val TEST_TAG_TOGGLE = "qs_tile_toggle_target"

@Composable
fun LargeTileContent(
    label: String,
    secondaryLabel: String?,
    icon: Icon,
    sideDrawable: Drawable?,
    colors: TileColors,
    squishiness: () -> Float,
    accessibilityUiState: AccessibilityUiState? = null,
    iconShape: RoundedCornerShape = RoundedCornerShape(CommonTileDefaults.InactiveCornerRadius),
    toggleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = tileHorizontalArrangement(),
    ) {
        // Icon
        val longPressLabel = longPressLabel().takeIf { onLongClick != null }
        val animatedBackgroundColor by
            animateColorAsState(colors.iconBackground, label = "QSTileDualTargetBackgroundColor")
        val focusBorderColor = MaterialTheme.colorScheme.secondary
        Box(
            modifier =
                Modifier.size(CommonTileDefaults.ToggleTargetSize).thenIf(toggleClick != null) {
                    Modifier.borderOnFocus(color = focusBorderColor, iconShape.topEnd)
                        .clip(iconShape)
                        .verticalSquish(squishiness)
                        .drawBehind { drawRect(animatedBackgroundColor) }
                        .combinedClickable(
                            onClick = toggleClick!!,
                            onLongClick = onLongClick,
                            onLongClickLabel = longPressLabel,
                            hapticFeedbackEnabled = !Flags.msdlFeedback(),
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
                size = { CommonTileDefaults.LargeTileIconSize },
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

        if (sideDrawable != null) {
            Image(
                painter = rememberDrawablePainter(sideDrawable),
                contentDescription = null,
                modifier = Modifier.width(SideIconWidth).height(SideIconHeight),
            )
        }
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
    val animatedLabelColor by animateColorAsState(colors.label, label = "QSTileLabelColor")
    val animatedSecondaryLabelColor by
        animateColorAsState(colors.secondaryLabel, label = "QSTileSecondaryLabelColor")
    Column(verticalArrangement = Arrangement.Center, modifier = modifier.fillMaxHeight()) {
        BasicText(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = { animatedLabelColor },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!TextUtils.isEmpty(secondaryLabel)) {
            BasicText(
                secondaryLabel ?: "",
                color = { animatedSecondaryLabelColor },
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier.thenIf(
                        accessibilityUiState?.stateDescription?.contains(secondaryLabel ?: "") ==
                            true
                    ) {
                        Modifier.clearAndSetSemantics {}
                    },
            )
        }
    }
}

@Composable
fun SmallTileContent(
    modifier: Modifier = Modifier,
    icon: Icon,
    color: Color,
    size: () -> Dp = { CommonTileDefaults.IconSize },
    animateToEnd: Boolean = false,
) {
    val animatedColor by animateColorAsState(color, label = "QSTileIconColor")
    val iconModifier = modifier.size({ size().roundToPx() }, { size().roundToPx() })
    val context = LocalContext.current
    val loadedDrawable =
        remember(icon, context) {
            when (icon) {
                is Icon.Loaded -> icon.drawable
                is Icon.Resource -> context.getDrawable(icon.res)
            }
        }
    if (loadedDrawable is Animatable) {
        val painter =
            when (icon) {
                is Icon.Resource -> {
                    val image = AnimatedImageVector.animatedVectorResource(id = icon.res)
                    key(icon) {
                        if (animateToEnd) {
                            rememberAnimatedVectorPainter(animatedImageVector = image, atEnd = true)
                        } else {
                            var atEnd by remember(icon.res) { mutableStateOf(false) }
                            LaunchedEffect(key1 = icon.res) { atEnd = true }
                            rememberAnimatedVectorPainter(
                                animatedImageVector = image,
                                atEnd = atEnd,
                            )
                        }
                    }
                }

                is Icon.Loaded -> {
                    LaunchedEffect(loadedDrawable) {
                        if (loadedDrawable is AnimatedVectorDrawable) {
                            loadedDrawable.forceAnimationOnUI()
                        }
                    }
                    rememberDrawablePainter(loadedDrawable)
                }
            }

        Image(
            painter = painter,
            contentDescription = icon.contentDescription?.load(),
            colorFilter = ColorFilter.tint(color = animatedColor),
            modifier = iconModifier,
        )
    } else {
        Icon(icon = icon, tint = animatedColor, modifier = iconModifier)
    }
}

object CommonTileDefaults {
    val IconSize = 32.dp
    val LargeTileIconSize = 28.dp
    val SideIconWidth = 32.dp
    val SideIconHeight = 20.dp
    val ToggleTargetSize = 56.dp
    val TileHeight = 72.dp
    val TilePadding = 8.dp
    val TileArrangementPadding = 6.dp
    val InactiveCornerRadius = 50.dp

    @Composable fun longPressLabel() = stringResource(id = R.string.accessibility_long_click_tile)
}

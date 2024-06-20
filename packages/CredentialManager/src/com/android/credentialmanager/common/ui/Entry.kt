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

package com.android.credentialmanager.common.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.credentialmanager.ui.theme.EntryShape
import com.android.credentialmanager.ui.theme.Shapes

@Composable
fun Entry(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    entryHeadlineText: String,
    entrySecondLineText: String? = null,
    entryThirdLineText: String? = null,
    /** Supply one and only one of the [iconImageBitmap], [iconImageVector], or [iconPainter] for
     *  drawing the leading icon. */
    iconImageBitmap: ImageBitmap? = null,
    shouldApplyIconImageBitmapTint: Boolean = false,
    iconImageVector: ImageVector? = null,
    iconPainter: Painter? = null,
    /** This will replace the [entrySecondLineText] value and render the text along with a
     *  mask on / off toggle for hiding / displaying the password value. */
    passwordValue: String? = null,
    /** If true, draws a trailing lock icon. */
    isLockedAuthEntry: Boolean = false,
    enforceOneLine: Boolean = false,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    /** Get flow only, if present, where be drawn as a line above the headline. */
    affiliatedDomainText: String? = null,
) {
    val iconPadding = Modifier.wrapContentSize().padding(
        // Horizontal padding should be 16dp, but the suggestion chip itself
        // has 8dp horizontal elements padding
        start = 8.dp, top = 16.dp, bottom = 16.dp
    )
    val iconSize = Modifier.size(24.dp)
    SuggestionChip(
        modifier = modifier.fillMaxWidth().wrapContentHeight(),
        onClick = onClick,
        shape = EntryShape.FullSmallRoundedCorner,
        label = {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(
                    // Total end padding should be 16dp, but the suggestion chip itself
                    // has 8dp horizontal elements padding
                    horizontal = 8.dp, vertical = 16.dp,
                ),
                // Make sure the trailing icon and text column are centered vertically.
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Apply weight so that the trailing icon can always show.
                Column(modifier = Modifier.wrapContentHeight().fillMaxWidth().weight(1f)) {
                    if (!affiliatedDomainText.isNullOrBlank()) {
                        BodySmallText(
                            text = affiliatedDomainText,
                            enforceOneLine = enforceOneLine,
                            onTextLayout = onTextLayout,
                        )
                    }
                    SmallTitleText(
                        text = entryHeadlineText,
                        enforceOneLine = enforceOneLine,
                        onTextLayout = onTextLayout,
                    )
                    if (passwordValue != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            val visualTransformation = remember { PasswordVisualTransformation() }
                            val originalPassword by remember {
                                mutableStateOf(passwordValue)
                            }
                            val displayedPassword = remember {
                                mutableStateOf(
                                    visualTransformation.filter(
                                        AnnotatedString(originalPassword)
                                    ).text.text
                                )
                            }
                            BodySmallText(
                                text = displayedPassword.value,
                                // Apply weight to allow visibility button to render first so that
                                // it doesn't get squeezed out by a super long password.
                                modifier = Modifier.wrapContentSize().weight(1f, fill = false),
                            )
                            ToggleVisibilityButton(
                                modifier = Modifier.padding(start = 12.dp).size(24.dp),
                                onToggle = {
                                    if (it) {
                                        displayedPassword.value = originalPassword
                                    } else {
                                        displayedPassword.value = visualTransformation.filter(
                                            AnnotatedString(originalPassword)
                                        ).text.text
                                    }
                                },
                            )
                        }
                    } else if (!entrySecondLineText.isNullOrBlank()) {
                        BodySmallText(
                            text = entrySecondLineText,
                            enforceOneLine = enforceOneLine,
                            onTextLayout = onTextLayout,
                        )
                    }
                    if (!entryThirdLineText.isNullOrBlank()) {
                        BodySmallText(
                            text = entryThirdLineText,
                            enforceOneLine = enforceOneLine,
                            onTextLayout = onTextLayout,
                        )
                    }
                }
                if (isLockedAuthEntry) {
                    Box(modifier = Modifier.wrapContentSize().padding(start = 16.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            // Decorative purpose only.
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = LocalAndroidColorScheme.current.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        icon =
        if (iconImageBitmap != null) {
            if (shouldApplyIconImageBitmapTint) {
                {
                    Box(modifier = iconPadding) {
                        Icon(
                            modifier = iconSize,
                            bitmap = iconImageBitmap,
                            tint = LocalAndroidColorScheme.current.onSurfaceVariant,
                            // Decorative purpose only.
                            contentDescription = null,
                        )
                    }
                }
            } else {
                {
                    Box(modifier = iconPadding) {
                        Image(
                            modifier = iconSize,
                            bitmap = iconImageBitmap,
                            // Decorative purpose only.
                            contentDescription = null,
                        )
                    }
                }
            }
        } else if (iconImageVector != null) {
            {
                Box(modifier = iconPadding) {
                    Icon(
                        modifier = iconSize,
                        imageVector = iconImageVector,
                        tint = LocalAndroidColorScheme.current.onSurfaceVariant,
                        // Decorative purpose only.
                        contentDescription = null,
                    )
                }
            }
        } else if (iconPainter != null) {
            {
                Box(modifier = iconPadding) {
                    Icon(
                        modifier = iconSize,
                        painter = iconPainter,
                        tint = LocalAndroidColorScheme.current.onSurfaceVariant,
                        // Decorative purpose only.
                        contentDescription = null,
                    )
                }
            }
        } else {
            null
        },
        border = null,
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = LocalAndroidColorScheme.current.surfaceContainerHigh,
            labelColor = LocalAndroidColorScheme.current.onSurfaceVariant,
            iconContentColor = LocalAndroidColorScheme.current.onSurfaceVariant,
        ),
    )
}

/**
 * A variation of the normal entry in that its background is transparent and the paddings are
 * different (no horizontal padding).
 */
@Composable
fun ActionEntry(
    onClick: () -> Unit,
    entryHeadlineText: String,
    entrySecondLineText: String? = null,
    iconImageBitmap: ImageBitmap,
) {
    SuggestionChip(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        onClick = onClick,
        shape = Shapes.large,
        label = {
            Column(
                    modifier = Modifier.heightIn(min = 56.dp).wrapContentSize().padding(
                            start = 16.dp, top = 16.dp, bottom = 16.dp
                    ),
                    verticalArrangement = Arrangement.Center,
            ) {
                SmallTitleText(entryHeadlineText)
                if (!entrySecondLineText.isNullOrBlank()) {
                    BodySmallText(entrySecondLineText)
                }
            }
        },
        icon = {
            Box(modifier = Modifier.wrapContentSize().padding(vertical = 16.dp)) {
                Image(
                    modifier = Modifier.size(24.dp),
                    bitmap = iconImageBitmap,
                    // Decorative purpose only.
                    contentDescription = null,
                )
            }
        },
        border = null,
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = Color.Transparent,
        ),
    )
}

/**
 * A single row of one or two CTA buttons for continuing or cancelling the current step.
 */
@Composable
fun CtaButtonRow(
    leftButton: (@Composable () -> Unit)? = null,
    rightButton: (@Composable () -> Unit)? = null,
) {
    Row(
        horizontalArrangement =
        if (leftButton == null) Arrangement.End
        else if (rightButton == null) Arrangement.Start
        else Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (leftButton != null) {
            Box(modifier = Modifier.wrapContentSize().weight(1f, fill = false)) {
                leftButton()
            }
        }
        if (rightButton != null) {
            Box(modifier = Modifier.wrapContentSize().weight(1f, fill = false)) {
                rightButton()
            }
        }
    }
}

@Composable
fun MoreOptionTopAppBar(
    text: String,
    onNavigationIconClicked: () -> Unit,
    navigationIcon: ImageVector,
    navigationIconContentDescription: String,
    bottomPadding: Dp,
) {
    Row(
            modifier = Modifier.padding(top = 12.dp, bottom = bottomPadding),
            verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp, start = 4.dp).size(48.dp),
                onClick = onNavigationIconClicked
        ) {
            Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
            ) {
                Icon(
                        imageVector = navigationIcon,
                        contentDescription = navigationIconContentDescription,
                        modifier = Modifier.size(24.dp).autoMirrored(),
                        tint = LocalAndroidColorScheme.current.onSurfaceVariant,
                )
            }
        }
        LargeTitleText(text = text, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

private fun Modifier.autoMirrored() = composed {
    when (LocalLayoutDirection.current) {
        LayoutDirection.Rtl -> graphicsLayer(scaleX = -1f)
        else -> this
    }
}
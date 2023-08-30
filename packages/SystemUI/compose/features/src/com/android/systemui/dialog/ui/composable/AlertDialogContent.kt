/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.dialog.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import kotlin.math.roundToInt

/**
 * The content of an AlertDialog which can be used together with
 * [SystemUIDialogFactory.create][com.android.systemui.statusbar.phone.create] to create an alert
 * dialog in Compose.
 *
 * @see com.android.systemui.statusbar.phone.create
 */
@Composable
fun AlertDialogContent(
    title: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    positiveButton: (@Composable () -> Unit)? = null,
    negativeButton: (@Composable () -> Unit)? = null,
    neutralButton: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(DialogPaddings),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Icon.
        if (icon != null) {
            val defaultSize = 32.dp
            Box(
                Modifier.defaultMinSize(minWidth = defaultSize, minHeight = defaultSize),
                propagateMinConstraints = true,
            ) {
                val iconColor = LocalAndroidColorScheme.current.primary
                CompositionLocalProvider(LocalContentColor provides iconColor) { icon() }
            }

            Spacer(Modifier.height(16.dp))
        }

        // Title.
        val titleColor = LocalAndroidColorScheme.current.onSurface
        CompositionLocalProvider(LocalContentColor provides titleColor) {
            ProvideTextStyle(
                MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center)
            ) {
                title()
            }
        }
        Spacer(Modifier.height(16.dp))

        // Content.
        val contentColor = LocalAndroidColorScheme.current.onSurfaceVariant
        Box(Modifier.defaultMinSize(minHeight = 48.dp)) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                ProvideTextStyle(
                    MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center)
                ) {
                    content()
                }
            }
        }
        Spacer(Modifier.height(32.dp))

        // Buttons.
        if (positiveButton != null || negativeButton != null || neutralButton != null) {
            AlertDialogButtons(
                positiveButton = positiveButton,
                negativeButton = negativeButton,
                neutralButton = neutralButton,
            )
        }
    }
}

@Composable
private fun AlertDialogButtons(
    positiveButton: (@Composable () -> Unit)?,
    negativeButton: (@Composable () -> Unit)?,
    neutralButton: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            positiveButton?.let { Box(Modifier.layoutId("positive")) { it() } }
            negativeButton?.let { Box(Modifier.layoutId("negative")) { it() } }
            neutralButton?.let { Box(Modifier.layoutId("neutral")) { it() } }
        },
        modifier,
    ) { measurables, constraints ->
        check(constraints.hasBoundedWidth) {
            "AlertDialogButtons should not be composed in an horizontally scrollable layout"
        }
        val maxWidth = constraints.maxWidth

        // Measure the buttons.
        var positive: Placeable? = null
        var negative: Placeable? = null
        var neutral: Placeable? = null
        for (i in measurables.indices) {
            val measurable = measurables[i]
            when (val layoutId = measurable.layoutId) {
                "positive" -> positive = measurable.measure(constraints)
                "negative" -> negative = measurable.measure(constraints)
                "neutral" -> neutral = measurable.measure(constraints)
                else -> error("Unexpected layoutId=$layoutId")
            }
        }

        fun Placeable?.width() = this?.width ?: 0
        fun Placeable?.height() = this?.height ?: 0

        // The min horizontal spacing between buttons.
        val horizontalSpacing = 8.dp.toPx()
        val totalHorizontalSpacing = (measurables.size - 1) * horizontalSpacing
        val requiredWidth =
            positive.width() + negative.width() + neutral.width() + totalHorizontalSpacing

        if (requiredWidth <= maxWidth) {
            // Stack horizontally: [neutral][flexSpace][negative][positive].
            val height = maxOf(positive.height(), negative.height(), neutral.height())
            layout(maxWidth, height) {
                positive?.let { it.placeRelative(maxWidth - it.width, 0) }

                negative?.let { negative ->
                    if (positive == null) {
                        negative.placeRelative(maxWidth - negative.width, 0)
                    } else {
                        negative.placeRelative(
                            maxWidth -
                                negative.width -
                                positive.width -
                                horizontalSpacing.roundToInt(),
                            0
                        )
                    }
                }

                neutral?.placeRelative(0, 0)
            }
        } else {
            // Stack vertically, aligned on the right (in LTR layouts):
            //   [positive]
            //   [negative]
            //    [neutral]
            //
            // TODO(b/283817398): Introduce a ResponsiveDialogButtons composable to create buttons
            // that have different styles when stacked horizontally, as shown in
            // go/sysui-dialog-styling.
            val height = positive.height() + negative.height() + neutral.height()
            layout(maxWidth, height) {
                var y = 0
                fun Placeable.place() {
                    placeRelative(maxWidth - width, y)
                    y += this.height
                }

                positive?.place()
                negative?.place()
                neutral?.place()
            }
        }
    }
}

private val DialogPaddings = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 18.dp)

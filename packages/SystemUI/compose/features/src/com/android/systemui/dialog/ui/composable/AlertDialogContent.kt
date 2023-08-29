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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme

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
        // TODO(b/283817398): If there is not enough space, the buttons should automatically stack
        // as shown in go/sysui-dialog-styling.
        if (positiveButton != null || negativeButton != null || neutralButton != null) {
            Row(Modifier.fillMaxWidth()) {
                if (neutralButton != null) {
                    neutralButton()
                    Spacer(Modifier.width(8.dp))
                }

                Spacer(Modifier.weight(1f))

                if (negativeButton != null) {
                    negativeButton()
                }

                if (positiveButton != null) {
                    if (negativeButton != null) {
                        Spacer(Modifier.width(8.dp))
                    }

                    positiveButton()
                }
            }
        }
    }
}

private val DialogPaddings = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 18.dp)

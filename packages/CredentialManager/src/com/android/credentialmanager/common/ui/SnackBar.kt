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

package com.android.credentialmanager.common.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.compose.rememberSystemUiController
import com.android.credentialmanager.R
import com.android.credentialmanager.common.material.Scrim
import com.android.credentialmanager.ui.theme.Shapes
import kotlinx.coroutines.delay

@Composable
fun Snackbar(
    contentText: String,
    action: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    dismissOnTimeout: Boolean = false,
) {
    val sysUiController = rememberSystemUiController()
    setTransparentSystemBarsColor(sysUiController)
    BoxWithConstraints {
        Box(Modifier.fillMaxSize()) {
            Scrim(
                color = Color.Transparent,
                onDismiss = onDismiss,
                visible = true
            )
        }
        Box(
            modifier =
            Modifier.align(Alignment.BottomCenter)
                .wrapContentSize()
                .padding(
                    bottom =
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                    start = 24.dp,
                    end = 24.dp,
                )
        ) {
            Card(
                shape = Shapes.medium,
                modifier = Modifier.wrapContentSize(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                )
            ) {
                Row(
                    modifier = Modifier.wrapContentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SnackbarContentText(contentText, modifier = Modifier.padding(
                        top = 18.dp, bottom = 18.dp, start = 24.dp,
                    ))
                    if (action != null) {
                        action()
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.padding(
                        top = 4.dp, bottom = 4.dp, start = 2.dp, end = 10.dp,
                    )) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(
                                R.string.accessibility_snackbar_dismiss
                            ),
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                }
            }
        }
    }
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(true) {
        if (dismissOnTimeout) {
            // Same as SnackbarDuration.Short
            val originalDuration = 4000L
            val duration = if (accessibilityManager == null) originalDuration else
                accessibilityManager.calculateRecommendedTimeoutMillis(
                    originalDuration,
                    containsIcons = true,
                    containsText = true,
                    containsControls = action != null,
                )
            delay(duration)
            onDismiss()
        }
    }
}
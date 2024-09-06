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

package com.android.systemui.communal.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.ComponentSystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create

/** Dialog shown upon tapping a disabled widget which allows users to enable the widget. */
@Composable
fun EnableWidgetDialog(
    isEnableWidgetDialogVisible: Boolean,
    dialogFactory: SystemUIDialogFactory,
    title: String,
    positiveButtonText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var dialog: ComponentSystemUIDialog? by remember { mutableStateOf(null) }
    val context = LocalView.current.context

    DisposableEffect(isEnableWidgetDialogVisible) {
        if (isEnableWidgetDialogVisible) {
            dialog =
                dialogFactory.create(
                    context = context,
                ) {
                    DialogComposable(title, positiveButtonText, onConfirm, onCancel)
                }
            dialog?.apply {
                setCancelable(true)
                setCanceledOnTouchOutside(true)
                setOnCancelListener { onCancel() }
                show()
            }
        }

        onDispose {
            dialog?.dismiss()
            dialog = null
        }
    }
}

@Composable
private fun DialogComposable(
    title: String,
    positiveButtonText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth()
            .padding(top = 18.dp, bottom = 8.dp)
            .background(LocalAndroidColorScheme.current.surfaceBright, RoundedCornerShape(28.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().wrapContentHeight(),
                contentAlignment = Alignment.TopStart
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalAndroidColorScheme.current.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }

            Box(
                modifier = Modifier.padding(end = 12.dp).fillMaxWidth().wrapContentHeight(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        contentPadding = PaddingValues(16.dp),
                        onClick = onCancel,
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                        )
                    }
                    TextButton(
                        contentPadding = PaddingValues(16.dp),
                        onClick = onConfirm,
                    ) {
                        Text(
                            text = positiveButtonText,
                        )
                    }
                }
            }
        }
    }
}

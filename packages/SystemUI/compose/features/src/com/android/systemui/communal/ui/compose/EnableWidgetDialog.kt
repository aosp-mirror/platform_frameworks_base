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

import android.app.Dialog
import android.content.DialogInterface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialogFactory

/**
 * Dialog shown upon tapping a disabled widget. It enables users to navigate to settings and modify
 * allowed widget categories.
 */
@Composable
fun EnableWidgetDialog(
    isEnableWidgetDialogVisible: Boolean,
    dialogFactory: SystemUIDialogFactory,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var dialog: Dialog? by remember { mutableStateOf(null) }
    val context = LocalView.current.context

    DisposableEffect(isEnableWidgetDialogVisible) {
        if (isEnableWidgetDialogVisible) {
            dialog =
                dialogFactory.create(context = context).apply {
                    setTitle(context.getString(R.string.dialog_title_to_allow_any_widget))
                    setButton(
                        DialogInterface.BUTTON_NEGATIVE,
                        context.getString(R.string.cancel)
                    ) { _, _ ->
                        onCancel()
                    }
                    setButton(
                        DialogInterface.BUTTON_POSITIVE,
                        context.getString(R.string.button_text_to_open_settings)
                    ) { _, _ ->
                        onConfirm()
                    }
                    setCancelable(false)
                    show()
                }
        }

        onDispose {
            dialog?.dismiss()
            dialog = null
        }
    }
}

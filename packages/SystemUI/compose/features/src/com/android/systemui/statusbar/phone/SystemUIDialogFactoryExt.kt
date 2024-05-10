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

package com.android.systemui.statusbar.phone

import android.content.Context
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import com.android.compose.theme.PlatformTheme

/**
 * Create a [SystemUIDialog] with the given [content].
 *
 * Note that the returned dialog will already have a background so the content should not draw an
 * additional background.
 *
 * Example:
 * ```
 * val dialog = systemUiDialogFactory.create {
 *   AlertDialogContent(
 *     title = { Text("My title") },
 *     content = { Text("My content") },
 *   )
 * }
 *
 * dialogLaunchAnimator.showFromView(dialog, viewThatWasClicked)
 * ```
 *
 * @param context the [Context] in which the dialog will be constructed.
 * @param dismissOnDeviceLock whether the dialog should be automatically dismissed when the device
 *   is locked (true by default).
 */
fun SystemUIDialogFactory.create(
    context: Context = this.applicationContext,
    theme: Int = SystemUIDialog.DEFAULT_THEME,
    dismissOnDeviceLock: Boolean = SystemUIDialog.DEFAULT_DISMISS_ON_DEVICE_LOCK,
    content: @Composable (SystemUIDialog) -> Unit,
): ComponentSystemUIDialog {
    val dialog = create(context, theme, dismissOnDeviceLock)

    // Create the dialog so that it is properly constructed before we set the Compose content.
    // Otherwise, the ComposeView won't render properly.
    dialog.create()

    // Set the content. Note that the background of the dialog is drawn on the DecorView of the
    // dialog directly, which makes it automatically work nicely with DialogLaunchAnimator.
    dialog.setContentView(
        ComposeView(context).apply {
            setContent {
                PlatformTheme {
                    val defaultContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    CompositionLocalProvider(LocalContentColor provides defaultContentColor) {
                        content(dialog)
                    }
                }
            }
        }
    )

    return dialog
}

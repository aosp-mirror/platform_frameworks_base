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
import android.content.res.Configuration
import android.os.Bundle
import androidx.annotation.GravityInt
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.theme.PlatformTheme
import com.android.systemui.res.R

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
 * dialogTransitionAnimator.showFromView(dialog, viewThatWasClicked)
 * ```
 *
 * @param context the [Context] in which the dialog will be constructed.
 * @param dismissOnDeviceLock whether the dialog should be automatically dismissed when the device
 *   is locked (true by default).
 * @param dialogGravity is one of the [android.view.Gravity] and determines dialog position on the
 *   screen.
 */
fun SystemUIDialogFactory.create(
    context: Context = this.applicationContext,
    theme: Int = SystemUIDialog.DEFAULT_THEME,
    dismissOnDeviceLock: Boolean = SystemUIDialog.DEFAULT_DISMISS_ON_DEVICE_LOCK,
    @GravityInt dialogGravity: Int? = null,
    content: @Composable (SystemUIDialog) -> Unit,
): ComponentSystemUIDialog {
    return create(
        context = context,
        theme = theme,
        dismissOnDeviceLock = dismissOnDeviceLock,
        delegate =
            object : DialogDelegate<SystemUIDialog> {
                override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
                    super.onCreate(dialog, savedInstanceState)
                    dialogGravity?.let { dialog.window?.setGravity(it) }
                }
            },
        content = content,
    )
}

/** Same as [create] but creates a bottom sheet dialog. */
fun SystemUIDialogFactory.createBottomSheet(
    context: Context = this.applicationContext,
    theme: Int = R.style.Theme_SystemUI_BottomSheet,
    dismissOnDeviceLock: Boolean = SystemUIDialog.DEFAULT_DISMISS_ON_DEVICE_LOCK,
    content: @Composable (SystemUIDialog) -> Unit,
): ComponentSystemUIDialog {
    return create(
        context = context,
        theme = theme,
        dismissOnDeviceLock = dismissOnDeviceLock,
        delegate = EdgeToEdgeDialogDelegate(),
        content = { dialog ->
            Box(
                modifier = Modifier.bottomSheetClickable { dialog.dismiss() },
                contentAlignment = Alignment.BottomCenter
            ) {
                val radius = dimensionResource(R.dimen.bottom_sheet_corner_radius)
                Surface(
                    modifier =
                        Modifier.bottomSheetPaddings()
                            // consume input so it doesn't get to the parent Composable
                            .bottomSheetClickable {}
                            // TODO(b/337205027) change width
                            .widthIn(max = 800.dp),
                    shape = RoundedCornerShape(topStart = radius, topEnd = radius),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Box(
                        Modifier.padding(
                            bottom =
                                with(LocalDensity.current) {
                                    WindowInsets.safeDrawing.getBottom(this).toDp()
                                }
                        )
                    ) {
                        content(dialog)
                    }
                }
            }
        },
    )
}

private fun SystemUIDialogFactory.create(
    context: Context,
    theme: Int,
    dismissOnDeviceLock: Boolean,
    delegate: DialogDelegate<SystemUIDialog>,
    content: @Composable (SystemUIDialog) -> Unit,
): ComponentSystemUIDialog {
    val dialog = create(context, theme, dismissOnDeviceLock, delegate)

    // Create the dialog so that it is properly constructed before we set the Compose content.
    // Otherwise, the ComposeView won't render properly.
    dialog.create()

    // Set the content. Note that the background of the dialog is drawn on the DecorView of the
    // dialog directly, which makes it automatically work nicely with DialogTransitionAnimator.
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

/** Adds paddings for the bottom sheet surface. */
@Composable
private fun Modifier.bottomSheetPaddings(): Modifier {
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    return with(LocalDensity.current) {
        val insets = WindowInsets.safeDrawing
        // TODO(b/337205027) change paddings
        val horizontalPadding: Dp = if (isPortrait) 0.dp else 48.dp
        padding(
            start = insets.getLeft(this, LocalLayoutDirection.current).toDp() + horizontalPadding,
            top = insets.getTop(this).toDp(),
            end = insets.getRight(this, LocalLayoutDirection.current).toDp() + horizontalPadding
        )
    }
}

/**
 * For some reason adding clickable modifier onto the VolumePanel affects the traversal order:
 * b/331155283.
 *
 * TODO(b/334870995) revert this to Modifier.clickable
 */
@Composable
private fun Modifier.bottomSheetClickable(onClick: () -> Unit) =
    pointerInput(onClick) { detectTapGestures { onClick() } }

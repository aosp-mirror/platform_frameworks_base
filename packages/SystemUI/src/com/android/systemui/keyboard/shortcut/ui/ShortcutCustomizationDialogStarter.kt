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

package com.android.systemui.keyboard.shortcut.ui

import android.app.Dialog
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo
import com.android.systemui.keyboard.shortcut.ui.composable.AssignNewShortcutDialog
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState
import com.android.systemui.keyboard.shortcut.ui.viewmodel.ShortcutCustomizationViewModel
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class ShortcutCustomizationDialogStarter
@AssistedInject
constructor(
    viewModelFactory: ShortcutCustomizationViewModel.Factory,
    private val dialogFactory: SystemUIDialogFactory,
) : ExclusiveActivatable() {

    private var dialog: Dialog? = null
    private val viewModel = viewModelFactory.create()

    override suspend fun onActivated(): Nothing {
        viewModel.shortcutCustomizationUiState.collect { uiState ->
            if (
                uiState is ShortcutCustomizationUiState.AddShortcutDialog &&
                    !uiState.isDialogShowing
            ) {
                dialog = createAddShortcutDialog().also { it.show() }
                viewModel.onAddShortcutDialogShown()
            } else if (uiState is ShortcutCustomizationUiState.Inactive) {
                dialog?.dismiss()
                dialog = null
            }
        }
    }

    fun onShortcutCustomizationRequested(requestInfo: ShortcutCustomizationRequestInfo) {
        viewModel.onShortcutCustomizationRequested(requestInfo)
    }

    private fun createAddShortcutDialog(): Dialog {
        return dialogFactory.create(dialogDelegate = ShortcutCustomizationDialogDelegate()) { dialog
            ->
            val uiState by viewModel.shortcutCustomizationUiState.collectAsStateWithLifecycle()
            AssignNewShortcutDialog(
                uiState = uiState,
                modifier = Modifier.width(364.dp).wrapContentHeight().padding(vertical = 24.dp),
                onKeyPress = { viewModel.onKeyPressed(it) },
                onCancel = { dialog.dismiss() },
            )
            dialog.setOnDismissListener { viewModel.onAddShortcutDialogDismissed() }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): ShortcutCustomizationDialogStarter
    }
}

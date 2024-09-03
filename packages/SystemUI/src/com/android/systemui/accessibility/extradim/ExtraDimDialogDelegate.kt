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
package com.android.systemui.accessibility.extradim

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.android.internal.accessibility.AccessibilityShortcutController
import com.android.internal.accessibility.common.ShortcutConstants
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dialog for removing Extra Dim shortcuts. */
class ExtraDimDialogDelegate
@Inject
constructor(
    private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val systemUIDialogFactory: SystemUIDialog.Factory,
    private val accessibilityManager: AccessibilityManager,
    private val userTracker: UserTracker,
) : SystemUIDialog.Delegate {

    private val onClickListener: DialogInterface.OnClickListener =
        DialogInterface.OnClickListener { dialog, _ ->
            applicationScope.launch {
                dialog.dismiss()
                onRemoveExtraDimShortcutButtonClicked()
                Toast.makeText(
                        context,
                        context.getText(R.string.accessibility_deprecate_extra_dim_dialog_toast),
                        Toast.LENGTH_LONG
                    )
                    .show()
            }
        }

    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        dialog.setTitle(R.string.accessibility_deprecate_extra_dim_dialog_title)
        dialog.setView(
            LayoutInflater.from(dialog.context)
                .inflate(R.layout.accessibility_deprecate_extra_dim_dialog, null)
        )
        dialog.setPositiveButton(
            R.string.accessibility_deprecate_extra_dim_dialog_button,
            onClickListener
        )
    }

    override fun createDialog(): SystemUIDialog {
        val dialog = systemUIDialogFactory.create(this)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    private suspend fun onRemoveExtraDimShortcutButtonClicked() =
        withContext(backgroundDispatcher) {
            accessibilityManager.enableShortcutsForTargets(
                /* enable= */ false,
                ShortcutConstants.UserShortcutType.ALL,
                setOf(
                    AccessibilityShortcutController.REDUCE_BRIGHT_COLORS_COMPONENT_NAME
                        .flattenToString()
                ),
                userTracker.userId
            )
        }
}

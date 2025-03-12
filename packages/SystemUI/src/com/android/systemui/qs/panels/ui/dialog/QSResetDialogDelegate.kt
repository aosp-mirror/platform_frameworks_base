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

package com.android.systemui.qs.panels.ui.dialog

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.android.compose.PlatformButton
import com.android.compose.PlatformTextButton
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dialog.ui.composable.AlertDialogContent
import com.android.systemui.qs.panels.domain.interactor.EditTilesResetInteractor
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.ComponentSystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import com.android.systemui.util.Assert
import javax.inject.Inject

@SysUISingleton
class QSResetDialogDelegate
@Inject
constructor(
    private val sysuiDialogFactory: SystemUIDialogFactory,
    private val resetInteractor: EditTilesResetInteractor,
) : SystemUIDialog.Delegate {
    private var currentDialog: ComponentSystemUIDialog? = null

    override fun createDialog(): SystemUIDialog {
        Assert.isMainThread()
        if (currentDialog != null) {
            Log.d(TAG, "Dialog is already open, dismissing it and creating a new one.")
            currentDialog?.dismiss()
        }

        currentDialog =
            sysuiDialogFactory
                .create { ResetConfirmationDialog(it) }
                .also {
                    it.lifecycle.addObserver(
                        object : DefaultLifecycleObserver {
                            override fun onStop(owner: LifecycleOwner) {
                                Assert.isMainThread()
                                currentDialog = null
                            }
                        }
                    )
                }
        return currentDialog!!
    }

    @Composable
    private fun ResetConfirmationDialog(dialog: SystemUIDialog) {
        AlertDialogContent(
            title = { Text(text = stringResource(id = R.string.qs_edit_mode_reset_dialog_title)) },
            content = {
                Text(text = stringResource(id = R.string.qs_edit_mode_reset_dialog_content))
            },
            positiveButton = {
                PlatformButton(
                    onClick = {
                        dialog.dismiss()
                        resetInteractor.reset()
                    }
                ) {
                    Text(stringResource(id = android.R.string.ok))
                }
            },
            negativeButton = {
                PlatformTextButton(onClick = { dialog.dismiss() }) {
                    Text(stringResource(id = android.R.string.cancel))
                }
            },
        )
    }

    fun showDialog() {
        if (currentDialog == null) {
            createDialog()
        }
        currentDialog?.show()
    }

    companion object {
        private const val TAG = "ResetDialogDelegate"
    }
}

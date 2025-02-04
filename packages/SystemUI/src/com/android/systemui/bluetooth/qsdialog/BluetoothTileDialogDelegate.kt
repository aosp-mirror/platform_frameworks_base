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

package com.android.systemui.bluetooth.qsdialog

import android.os.Bundle
import android.view.LayoutInflater
import com.android.internal.logging.UiEventLogger
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Dialog for showing active, connected and saved bluetooth devices. */
class BluetoothTileDialogDelegate
@AssistedInject
internal constructor(
    @Assisted private val initialUiProperties: BluetoothTileDialogViewModel.UiProperties,
    @Assisted private val cachedContentHeight: Int,
    @Assisted private val bluetoothTileDialogCallback: BluetoothTileDialogCallback,
    @Assisted private val dismissListener: Runnable,
    private val uiEventLogger: UiEventLogger,
    private val systemuiDialogFactory: SystemUIDialog.Factory,
    private val shadeDialogContextInteractor: ShadeDialogContextInteractor,
    private val bluetoothDetailsContentManagerFactory: BluetoothDetailsContentManager.Factory,
    private val shadeModeInteractor: ShadeModeInteractor,
) : SystemUIDialog.Delegate {

    lateinit var contentManager: BluetoothDetailsContentManager

    @AssistedFactory
    internal interface Factory {
        fun create(
            initialUiProperties: BluetoothTileDialogViewModel.UiProperties,
            cachedContentHeight: Int,
            dialogCallback: BluetoothTileDialogCallback,
            dimissListener: Runnable,
        ): BluetoothTileDialogDelegate
    }

    override fun createDialog(): SystemUIDialog {
        // TODO (b/393628355): remove this after the details view is supported for single shade.
        if (shadeModeInteractor.isDualShade) {
            // If `QsDetailedView` is enabled, it should show the details view.
            QsDetailedView.assertInLegacyMode()
        }

        return systemuiDialogFactory.create(this, shadeDialogContextInteractor.context)
    }

    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        SystemUIDialog.registerDismissListener(dialog, dismissListener)
        uiEventLogger.log(BluetoothTileDialogUiEvent.BLUETOOTH_TILE_DIALOG_SHOWN)
        val context = dialog.context

        LayoutInflater.from(context).inflate(R.layout.bluetooth_tile_dialog, null).apply {
            accessibilityPaneTitle = context.getText(R.string.accessibility_desc_quick_settings)
            dialog.setContentView(this)
        }

        contentManager =
            bluetoothDetailsContentManagerFactory.create(
                initialUiProperties,
                cachedContentHeight,
                bluetoothTileDialogCallback,
                /* isInDialog= */ true,
                /* doneButtonCallback= */ fun() {
                    dialog.dismiss()
                },
            )
        contentManager.bind(dialog.requireViewById(R.id.root))
    }

    override fun onStart(dialog: SystemUIDialog) {
        contentManager.start()
    }

    override fun onStop(dialog: SystemUIDialog) {
        contentManager.releaseView()
    }
}

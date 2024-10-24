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
import android.widget.Button
import android.widget.TextView
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import com.android.app.tracing.coroutines.launchTraced as launch

class AudioSharingDialogDelegate
@AssistedInject
constructor(
    @Assisted private val cachedBluetoothDevice: CachedBluetoothDevice,
    @Application private val coroutineScope: CoroutineScope,
    private val viewModelFactory: AudioSharingDialogViewModel.Factory,
    private val sysuiDialogFactory: SystemUIDialog.Factory,
    private val uiEventLogger: UiEventLogger,
) : SystemUIDialog.Delegate {

    override fun createDialog(): SystemUIDialog = sysuiDialogFactory.create(this)

    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        with(dialog.layoutInflater.inflate(R.layout.audio_sharing_dialog, null)) {
            dialog.setView(this)
            val subtitleTextView = requireViewById<TextView>(R.id.subtitle)
            val shareAudioButton = requireViewById<TextView>(R.id.share_audio_button)
            val switchActiveButton = requireViewById<Button>(R.id.switch_active_button)
            val job =
                coroutineScope.launch {
                    val viewModel = viewModelFactory.create(cachedBluetoothDevice, this)
                    viewModel.dialogState.collect {
                        when (it) {
                            is AudioSharingDialogState.Hide -> dialog.dismiss()
                            is AudioSharingDialogState.Show -> {
                                subtitleTextView.text = it.subtitle
                                switchActiveButton.text = it.switchButtonText
                                switchActiveButton.setOnClickListener {
                                    viewModel.switchActiveClicked()
                                    uiEventLogger.log(
                                        BluetoothTileDialogUiEvent
                                            .AUDIO_SHARING_DIALOG_SWITCH_ACTIVE_CLICKED
                                    )
                                    dialog.dismiss()
                                }
                                shareAudioButton.setOnClickListener {
                                    viewModel.shareAudioClicked()
                                    uiEventLogger.log(
                                        BluetoothTileDialogUiEvent
                                            .AUDIO_SHARING_DIALOG_SHARE_AUDIO_CLICKED
                                    )
                                    dialog.dismiss()
                                }
                            }
                        }
                    }
                }
            SystemUIDialog.registerDismissListener(dialog) { job.cancel() }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(cachedBluetoothDevice: CachedBluetoothDevice): AudioSharingDialogDelegate
    }
}

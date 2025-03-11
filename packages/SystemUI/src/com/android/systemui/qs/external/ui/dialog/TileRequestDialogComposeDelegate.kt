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

package com.android.systemui.qs.external.ui.dialog

import android.content.DialogInterface.BUTTON_NEGATIVE
import android.content.DialogInterface.BUTTON_POSITIVE
import android.content.DialogInterface.OnClickListener
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.compose.PlatformButton
import com.android.compose.PlatformOutlinedButton
import com.android.compose.theme.PlatformTheme
import com.android.systemui.dialog.ui.composable.AlertDialogContent
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.external.TileData
import com.android.systemui.qs.external.ui.viewmodel.TileRequestDialogViewModel
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LargeStaticTile
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class TileRequestDialogComposeDelegate
@AssistedInject
constructor(
    private val sysuiDialogFactory: SystemUIDialogFactory,
    private val tileRequestDialogViewModelFactory: TileRequestDialogViewModel.Factory,
    @Assisted private val tileData: TileData,
    @Assisted private val dialogListener: OnClickListener,
) : SystemUIDialog.Delegate {

    override fun createDialog(): SystemUIDialog {
        return sysuiDialogFactory.create { TileRequestDialogContent(it) }
    }

    @Composable
    private fun TileRequestDialogContent(dialog: SystemUIDialog) {
        PlatformTheme {
            AlertDialogContent(
                title = {},
                content = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = spacedBy(16.dp),
                    ) {
                        val viewModel =
                            rememberViewModel(traceName = "TileRequestDialog", key = tileData) {
                                tileRequestDialogViewModelFactory.create(dialog.context, tileData)
                            }

                        Text(
                            text =
                                stringResource(
                                    R.string.qs_tile_request_dialog_text,
                                    tileData.appName,
                                ),
                            textAlign = TextAlign.Start,
                        )

                        LargeStaticTile(
                            uiState = viewModel.uiState,
                            modifier =
                                Modifier.width(
                                    dimensionResource(
                                        id = R.dimen.qs_tile_service_request_tile_width
                                    )
                                ),
                        )
                    }
                },
                positiveButton = {
                    PlatformButton(
                        onClick = {
                            dialogListener.onClick(dialog, BUTTON_POSITIVE)
                            dialog.dismiss()
                        }
                    ) {
                        Text(stringResource(R.string.qs_tile_request_dialog_add))
                    }
                },
                negativeButton = {
                    PlatformOutlinedButton(
                        onClick = {
                            dialogListener.onClick(dialog, BUTTON_NEGATIVE)
                            dialog.dismiss()
                        }
                    ) {
                        Text(stringResource(R.string.qs_tile_request_dialog_not_add))
                    }
                },
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            tiledata: TileData,
            dialogListener: OnClickListener,
        ): TileRequestDialogComposeDelegate
    }
}

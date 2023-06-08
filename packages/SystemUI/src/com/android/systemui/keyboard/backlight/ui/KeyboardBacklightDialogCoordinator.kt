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
 *
 */

package com.android.systemui.keyboard.backlight.ui

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyboard.backlight.ui.view.KeyboardBacklightDialog
import com.android.systemui.keyboard.backlight.ui.viewmodel.BacklightDialogViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Based on the state produced from [BacklightDialogViewModel] shows or hides keyboard backlight
 * indicator
 */
@SysUISingleton
class KeyboardBacklightDialogCoordinator
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val context: Context,
    private val viewModel: BacklightDialogViewModel,
) {

    var dialog: KeyboardBacklightDialog? = null

    fun startListening() {
        applicationScope.launch {
            viewModel.dialogContent.collect { dialogViewModel ->
                if (dialogViewModel != null) {
                    if (dialog == null) {
                        dialog =
                            KeyboardBacklightDialog(
                                context,
                                initialCurrentLevel = dialogViewModel.currentValue,
                                initialMaxLevel = dialogViewModel.maxValue
                            )
                        dialog?.show()
                    } else {
                        dialog?.updateState(dialogViewModel.currentValue, dialogViewModel.maxValue)
                    }
                } else {
                    dialog?.dismiss()
                    dialog = null
                }
            }
        }
    }
}

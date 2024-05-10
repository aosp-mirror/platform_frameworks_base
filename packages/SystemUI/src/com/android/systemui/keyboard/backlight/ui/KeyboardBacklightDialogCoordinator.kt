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
import com.android.systemui.keyboard.backlight.ui.viewmodel.BacklightDialogContentViewModel
import com.android.systemui.keyboard.backlight.ui.viewmodel.BacklightDialogViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private fun defaultCreateDialog(context: Context): (Int, Int) -> KeyboardBacklightDialog {
    return { currentLevel: Int, maxLevel: Int ->
        KeyboardBacklightDialog(context, currentLevel, maxLevel)
    }
}

/**
 * Based on the state produced from [BacklightDialogViewModel] shows or hides keyboard backlight
 * indicator
 */
@SysUISingleton
class KeyboardBacklightDialogCoordinator
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val viewModel: BacklightDialogViewModel,
    private val createDialog: (Int, Int) -> KeyboardBacklightDialog
) {

    @Inject
    constructor(
        @Application applicationScope: CoroutineScope,
        context: Context,
        viewModel: BacklightDialogViewModel
    ) : this(applicationScope, viewModel, defaultCreateDialog(context))

    var dialog: KeyboardBacklightDialog? = null

    fun startListening() {
        applicationScope.launch {
            viewModel.dialogContent.collect { contentModel ->
                if (contentModel != null) {
                    showDialog(contentModel)
                } else {
                    dialog?.dismiss()
                    dialog = null
                }
            }
        }
    }

    private fun showDialog(model: BacklightDialogContentViewModel) {
        if (dialog == null) {
            dialog = createDialog(model.currentValue, model.maxValue)
        } else {
            dialog?.updateState(model.currentValue, model.maxValue)
        }
        // let's always show dialog - even if we're just updating it, it might have been dismissed
        // externally by tapping finger outside of it
        dialog?.show()
    }
}

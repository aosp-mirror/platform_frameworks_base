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

package com.android.systemui.keyboard.stickykeys.ui

import android.app.Dialog
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import com.android.systemui.compose.ComposeFacade
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyboard.stickykeys.StickyKeysLogger
import com.android.systemui.keyboard.stickykeys.ui.viewmodel.StickyKeysIndicatorViewModel
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@SysUISingleton
class StickyKeysIndicatorCoordinator
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val dialogFactory: SystemUIDialogFactory,
    private val viewModel: StickyKeysIndicatorViewModel,
    private val stickyKeysLogger: StickyKeysLogger,
) {

    private var dialog: Dialog? = null

    fun startListening() {
        // this check needs to be moved to PhysicalKeyboardCoreStartable
        if (!ComposeFacade.isComposeAvailable()) {
            Log.e("StickyKeysIndicatorCoordinator", "Compose is required for this UI")
            return
        }
        applicationScope.launch {
            viewModel.indicatorContent.collect { stickyKeys ->
                stickyKeysLogger.logNewUiState(stickyKeys)
                if (stickyKeys.isEmpty()) {
                    dialog?.dismiss()
                    dialog = null
                } else if (dialog == null) {
                    dialog = ComposeFacade.createStickyKeysDialog(dialogFactory, viewModel).apply {
                        setCanceledOnTouchOutside(false)
                        window?.setAttributes()
                        show()
                    }
                }
            }
        }
    }

    private fun Window.setAttributes() {
        setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
        addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setGravity(Gravity.TOP or Gravity.END)
        attributes = WindowManager.LayoutParams().apply {
            copyFrom(attributes)
            width = WRAP_CONTENT
            title = "StickyKeysIndicator"
        }
    }
}

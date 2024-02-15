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
import android.content.Context
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL
import androidx.activity.ComponentDialog
import com.android.systemui.compose.ComposeFacade
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyboard.stickykeys.ui.viewmodel.StickyKeysIndicatorViewModel
import com.android.systemui.res.R
import javax.inject.Inject

@SysUISingleton
class StickyKeyDialogFactory
@Inject
constructor(
    @Application val context: Context,
) {

    fun create(viewModel: StickyKeysIndicatorViewModel): Dialog {
        return createStickyKeyIndicator(viewModel)
    }

    private fun createStickyKeyIndicator(viewModel: StickyKeysIndicatorViewModel): Dialog {
        return ComponentDialog(context, R.style.Theme_SystemUI_Dialog).apply {
            // because we're requesting window feature it must be called before setting content
            window?.setStickyKeyWindowAttributes()
            setContentView(ComposeFacade.createStickyKeysIndicatorContent(context, viewModel))
        }
    }

    private fun Window.setStickyKeyWindowAttributes() {
        requestFeature(Window.FEATURE_NO_TITLE)
        setType(TYPE_STATUS_BAR_SUB_PANEL)
        addFlags(FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE)
        clearFlags(FLAG_DIM_BEHIND)
        setGravity(Gravity.TOP or Gravity.END)
        attributes =
            WindowManager.LayoutParams().apply {
                copyFrom(attributes)
                title = "StickyKeysIndicator"
            }
    }
}

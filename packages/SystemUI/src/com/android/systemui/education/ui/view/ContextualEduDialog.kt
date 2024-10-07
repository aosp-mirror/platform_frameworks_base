/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.ui.view

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.ToastPresenter
import com.android.systemui.education.ui.viewmodel.ContextualEduToastViewModel
import com.android.systemui.res.R

class ContextualEduDialog(context: Context, private val model: ContextualEduToastViewModel) :
    AlertDialog(context, R.style.ContextualEduDialog) {
    override fun onCreate(savedInstanceState: Bundle?) {
        setUpWindowProperties()
        setWindowPosition()
        // title is used for a11y announcement
        window?.setTitle(context.getString(R.string.contextual_education_dialog_title))
        // TODO: b/369791926 - replace the below toast view with a custom dialog view
        val toastView = ToastPresenter.getTextToastView(context, model.message)
        setView(toastView)
        super.onCreate(savedInstanceState)
    }

    private fun setUpWindowProperties() {
        window?.apply {
            setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        setCanceledOnTouchOutside(false)
    }

    private fun setWindowPosition() {
        window?.apply {
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            this.attributes =
                WindowManager.LayoutParams().apply {
                    width = WindowManager.LayoutParams.WRAP_CONTENT
                    height = WindowManager.LayoutParams.WRAP_CONTENT
                    copyFrom(attributes)
                    y =
                        context.resources.getDimensionPixelSize(
                            R.dimen.contextual_edu_dialog_bottom_margin
                        )
                }
        }
    }
}

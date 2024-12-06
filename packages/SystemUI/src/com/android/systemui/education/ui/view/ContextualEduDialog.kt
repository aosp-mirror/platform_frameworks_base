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

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.education.ui.viewmodel.ContextualEduToastViewModel
import com.android.systemui.res.R

class ContextualEduDialog(
    context: Context,
    private val model: ContextualEduToastViewModel,
    private val accessibilityManager: AccessibilityManager,
) : Dialog(context) {
    override fun onCreate(savedInstanceState: Bundle?) {
        setUpWindowProperties()
        setWindowPosition()
        // title is used for a11y announcement
        window?.setTitle(context.getString(R.string.contextual_education_dialog_title))
        setContentView(R.layout.contextual_edu_dialog)
        setContent()
        sendAccessibilityEvent()
        super.onCreate(savedInstanceState)
    }

    private fun setContent() {
        findViewById<TextView>(R.id.edu_message)?.let { it.text = model.message }
        findViewById<ImageView>(R.id.edu_icon)?.let { it.setImageResource(model.icon) }
    }

    private fun sendAccessibilityEvent() {
        if (!accessibilityManager.isEnabled) {
            return
        }

        // It is a toast-like dialog which is unobtrusive and not focusable. So it needs to call
        // accessibilityManager.sendAccessibilityEvent explicitly to announce the message.
        accessibilityManager.sendAccessibilityEvent(
            AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
                text.add(model.message)
            }
        )
    }

    private fun setUpWindowProperties() {
        window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG)
            // NOT_TOUCH_MODAL allows users to interact with background elements and NOT_FOCUSABLE
            // avoids changing the existing focus when dialog is shown.
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
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

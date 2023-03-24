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
 */

package com.android.systemui.controls.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import com.android.systemui.R

class ControlsPopupMenu(context: Context) : ListPopupWindow(context) {

    private val resources: Resources = context.resources

    private val listDividerHeight: Int =
        resources.getDimensionPixelSize(R.dimen.control_popup_items_divider_height)
    private val horizontalMargin: Int =
        resources.getDimensionPixelSize(R.dimen.control_popup_horizontal_margin)
    private val maxWidth: Int = resources.getDimensionPixelSize(R.dimen.control_popup_max_width)

    private val dialogBackground: Drawable = resources.getDrawable(R.drawable.controls_popup_bg)!!
    private val dimDrawable: Drawable = ColorDrawable(resources.getColor(R.color.control_popup_dim))

    private var dismissListener: PopupWindow.OnDismissListener? = null

    init {
        setBackgroundDrawable(dialogBackground)

        inputMethodMode = INPUT_METHOD_NOT_NEEDED
        isModal = true
        setDropDownGravity(Gravity.START)

        // dismiss method isn't called when popup is hidden by outside touch. So we need to
        // override a listener to remove a dimming foreground
        super.setOnDismissListener {
            anchorView?.rootView?.foreground = null
            dismissListener?.onDismiss()
        }
    }

    override fun show() {
        // need to call show() first in order to construct the listView
        super.show()

        val paddedWidth = resources.displayMetrics.widthPixels - 2 * horizontalMargin
        width = maxWidth.coerceAtMost(paddedWidth)
        anchorView?.let {
            horizontalOffset = -width / 2 + it.width / 2
            verticalOffset = -it.height / 2
            if (it.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                horizontalOffset = -horizontalOffset
            }

            it.rootView.foreground = dimDrawable
        }

        with(listView!!) {
            clipToOutline = true
            background = dialogBackground
            dividerHeight = listDividerHeight
        }

        // actual show takes into account updated ListView specs
        super.show()
    }

    override fun setOnDismissListener(listener: PopupWindow.OnDismissListener?) {
        dismissListener = listener
    }
}

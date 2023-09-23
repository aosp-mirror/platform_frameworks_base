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
import android.view.Gravity.END
import android.view.Gravity.GravityFlags
import android.view.Gravity.NO_GRAVITY
import android.view.Gravity.START
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.ListPopupWindow
import android.widget.ListView
import android.widget.PopupWindow
import com.android.systemui.res.R
import kotlin.math.max

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
    @GravityFlags private var dropDownGravity: Int = NO_GRAVITY

    init {
        setBackgroundDrawable(dialogBackground)

        inputMethodMode = INPUT_METHOD_NOT_NEEDED
        isModal = true

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
        updateWidth()
        anchorView?.let {
            positionPopup(it)
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

    override fun setDropDownGravity(@GravityFlags gravity: Int) {
        super.setDropDownGravity(gravity)
        dropDownGravity = gravity
    }

    override fun setOnDismissListener(listener: PopupWindow.OnDismissListener?) {
        dismissListener = listener
    }

    private fun updateWidth() {
        val paddedWidth = resources.displayMetrics.widthPixels - 2 * horizontalMargin
        val maxWidth = maxWidth.coerceAtMost(paddedWidth)
        when (width) {
            ViewGroup.LayoutParams.MATCH_PARENT -> {
                width = maxWidth
            }
            ViewGroup.LayoutParams.WRAP_CONTENT -> {
                width = listView!!.measureDesiredWidth(maxWidth).coerceAtMost(maxWidth)
            }
        }
    }

    private fun positionPopup(anchorView: View) {
        when (dropDownGravity) {
            NO_GRAVITY -> {
                horizontalOffset = (-width + anchorView.width) / 2
                if (anchorView.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    horizontalOffset = -horizontalOffset
                }
            }
            END,
            START -> {
                horizontalOffset = 0
            }
        }
        verticalOffset = -anchorView.height / 2
    }

    private fun ListView.measureDesiredWidth(maxWidth: Int): Int {
        var maxItemWidth = 0
        repeat(adapter.count) {
            val view = adapter.getView(it, null, listView)
            view.measure(
                MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED
            )
            maxItemWidth = max(maxItemWidth, view.measuredWidth)
        }
        return maxItemWidth
    }
}

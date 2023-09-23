/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.user

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.ShapeDrawable
import android.view.View
import android.view.View.MeasureSpec
import android.widget.ListAdapter
import android.widget.ListPopupWindow
import android.widget.ListView
import com.android.systemui.res.R

/**
 * Popup menu for displaying items on the fullscreen user switcher.
 */
class UserSwitcherPopupMenu(
    private val context: Context
) : ListPopupWindow(context) {

    private val res = context.resources
    private var adapter: ListAdapter? = null

    init {
        setBackgroundDrawable(null)
        setModal(false)
        setOverlapAnchor(true)
    }

    override fun setAdapter(adapter: ListAdapter?) {
        super.setAdapter(adapter)
        this.adapter = adapter
    }

    /**
      * Show the dialog.
      */
    override fun show() {
        // need to call show() first in order to construct the listView
        super.show()
        listView?.apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            // Creates a transparent spacer between items
            val shape = ShapeDrawable()
            shape.alpha = 0
            divider = shape
            dividerHeight = res.getDimensionPixelSize(
                R.dimen.bouncer_user_switcher_popup_divider_height)

            val height = res.getDimensionPixelSize(R.dimen.bouncer_user_switcher_popup_header_height)
            addHeaderView(createSpacer(height), null, false)
            addFooterView(createSpacer(height), null, false)
            setWidth(findMaxWidth(this))
        }

        super.show()
    }

    private fun findMaxWidth(listView: ListView): Int {
        var maxWidth = 0
        adapter?.let {
            val parentWidth = res.getDisplayMetrics().widthPixels
            val spec = MeasureSpec.makeMeasureSpec(
                (parentWidth * 0.25).toInt(),
                MeasureSpec.AT_MOST
            )

            for (i in 0 until it.getCount()) {
                val child = it.getView(i, null, listView)
                child.measure(spec, MeasureSpec.UNSPECIFIED)
                maxWidth = Math.max(child.getMeasuredWidth(), maxWidth)
            }
        }
        return maxWidth
    }

    private fun createSpacer(height: Int): View {
        return object : View(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                setMeasuredDimension(1, height)
            }

            override fun draw(canvas: Canvas) {
            }
        }
    }
}

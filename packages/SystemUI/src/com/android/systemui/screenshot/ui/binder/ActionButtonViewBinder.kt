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

package com.android.systemui.screenshot.ui.binder

import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.res.R
import com.android.systemui.screenshot.ui.TransitioningIconDrawable
import com.android.systemui.screenshot.ui.viewmodel.ActionButtonViewModel

object ActionButtonViewBinder {
    /** Binds the given view to the given view-model */
    fun bind(view: View, viewModel: ActionButtonViewModel) {
        val iconView = view.requireViewById<ImageView>(R.id.overlay_action_chip_icon)
        val textView = view.requireViewById<TextView>(R.id.overlay_action_chip_text)
        if (iconView.drawable == null) {
            iconView.setImageDrawable(TransitioningIconDrawable())
        }
        val drawable = iconView.drawable as? TransitioningIconDrawable
        // Note we never re-bind a view to a different ActionButtonViewModel, different view
        // models would remove/create separate views.
        drawable?.setIcon(viewModel.appearance.icon)
        textView.text = viewModel.appearance.label

        viewModel.appearance.customBackground?.also {
            if (it.canApplyTheme()) {
                it.applyTheme(view.rootView.context.theme)
            }
            view.background = it
        }

        setMargins(iconView, textView, viewModel.appearance.label?.isNotEmpty() ?: false)
        if (viewModel.onClicked != null) {
            view.setOnClickListener { viewModel.onClicked.invoke() }
        } else {
            view.setOnClickListener(null)
        }
        view.tag = viewModel.id
        view.contentDescription = viewModel.appearance.description
        view.visibility = View.VISIBLE
        view.alpha = 1f
    }

    private fun setMargins(iconView: View, textView: View, hasText: Boolean) {
        val iconParams = iconView.layoutParams as LinearLayout.LayoutParams
        val textParams = textView.layoutParams as LinearLayout.LayoutParams
        if (hasText) {
            iconParams.marginStart = iconView.dpToPx(R.dimen.overlay_action_chip_padding_start)
            iconParams.marginEnd = iconView.dpToPx(R.dimen.overlay_action_chip_spacing)
            textParams.marginStart = 0
            textParams.marginEnd = textView.dpToPx(R.dimen.overlay_action_chip_padding_end)
        } else {
            val paddingHorizontal =
                iconView.dpToPx(R.dimen.overlay_action_chip_icon_only_padding_horizontal)
            iconParams.marginStart = paddingHorizontal
            iconParams.marginEnd = paddingHorizontal
        }
        iconView.layoutParams = iconParams
        textView.layoutParams = textParams
    }

    private fun View.dpToPx(dimenId: Int): Int {
        return this.resources.getDimensionPixelSize(dimenId)
    }
}

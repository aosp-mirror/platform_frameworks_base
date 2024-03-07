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
 *
 */

package com.android.systemui.user.ui.binder

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ImageView
import androidx.core.content.res.ResourcesCompat
import com.android.settingslib.Utils
import com.android.systemui.res.R
import com.android.systemui.common.ui.binder.TextViewBinder
import com.android.systemui.user.ui.viewmodel.UserViewModel

/** Binds a user view to its view-model. */
object UserViewBinder {
    /** Binds the given view to the given view-model. */
    fun bind(view: View, viewModel: UserViewModel) {
        TextViewBinder.bind(view.requireViewById(R.id.user_switcher_text), viewModel.name)
        view
            .requireViewById<ImageView>(R.id.user_switcher_icon)
            .setImageDrawable(getSelectableDrawable(view.context, viewModel))
        view.alpha = viewModel.alpha
        if (viewModel.onClicked != null) {
            view.setOnClickListener { viewModel.onClicked.invoke() }
        } else {
            view.setOnClickListener(null)
        }
    }

    private fun getSelectableDrawable(context: Context, viewModel: UserViewModel): Drawable {
        val layerDrawable =
            checkNotNull(
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.user_switcher_icon_large,
                        context.theme,
                    )
                )
                .mutate() as LayerDrawable
        if (viewModel.isSelectionMarkerVisible) {
            (layerDrawable.findDrawableByLayerId(R.id.ring) as GradientDrawable).apply {
                val stroke =
                    context.resources.getDimensionPixelSize(
                        R.dimen.user_switcher_icon_selected_width
                    )
                val color =
                    Utils.getColorAttrDefaultColor(
                        context,
                        com.android.internal.R.attr.colorAccentPrimary
                    )

                setStroke(stroke, color)
            }
        }

        layerDrawable.setDrawableByLayerId(R.id.user_avatar, viewModel.image)
        return layerDrawable
    }
}

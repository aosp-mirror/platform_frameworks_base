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

package com.android.settingslib.widget

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

import com.android.settingslib.widget.preference.zerostate.R

class ZeroStatePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : Preference(context, attrs, defStyleAttr, defStyleRes), GroupSectionDividerMixin {

    private val iconTint: Int = context.getColor(
        com.android.settingslib.widget.theme.R.color.settingslib_materialColorOnSecondaryContainer
    )
    private var tintedIcon: Drawable? = null

    init {
        isSelectable = false
        layoutResource = R.layout.settingslib_expressive_preference_zerostate
        icon?.let { originalIcon ->
            tintedIcon = originalIcon.mutate().apply {
                colorFilter = PorterDuffColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
            }
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.itemView.isFocusable = false
        holder.itemView.isClickable = false

        (holder.findViewById(android.R.id.icon) as? ImageView)?.apply {
            setImageDrawable(tintedIcon ?: icon)
        }
    }
}
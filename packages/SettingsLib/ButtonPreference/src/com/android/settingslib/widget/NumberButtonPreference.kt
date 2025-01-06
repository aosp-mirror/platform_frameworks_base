/*
 * Copyright (C) 2025 The Android Open Source Project
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
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

import com.android.settingslib.widget.preference.button.R

class NumberButtonPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : Preference(context, attrs, defStyleAttr, defStyleRes), GroupSectionDividerMixin {

    var clickListener: View.OnClickListener? = null

    var count: Int = 0
        set(value) {
            field = value
            notifyChanged()
        }

    var btnContentDescription: Int = 0
        set(value) {
            field = value
            notifyChanged()
        }

    init {
        isPersistent = false // This preference doesn't store data
        order = Int.MAX_VALUE
        layoutResource = R.layout.settingslib_number_button
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false

        holder.findViewById(R.id.settingslib_number_button)?.apply {
            setOnClickListener(clickListener)
            if (btnContentDescription != 0) {
                setContentDescription(context.getString(btnContentDescription, count))
            }
        }
        (holder.findViewById(R.id.settingslib_number_title) as? TextView)?.text = title

        (holder.findViewById(R.id.settingslib_number_count) as? TextView)?.text = "$count"
    }
}
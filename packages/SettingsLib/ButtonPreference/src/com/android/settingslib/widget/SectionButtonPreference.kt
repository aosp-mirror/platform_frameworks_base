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
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.settingslib.widget.preference.button.R
import com.google.android.material.button.MaterialButton

/**
 * A Preference that displays a button with an optional icon.
 */
class SectionButtonPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : Preference(context, attrs, defStyleAttr, defStyleRes), GroupSectionDividerMixin {

    private var clickListener: ((View) -> Unit)? = null
        set(value) {
            field = value
            notifyChanged()
        }
    private var button: MaterialButton? = null
    init {
        isPersistent = false // This preference doesn't store data
        order = Int.MAX_VALUE
        layoutResource = R.layout.settingslib_section_button
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false

        button = holder.findViewById(R.id.settingslib_section_button) as? MaterialButton
        button?.apply{
            text = title
            isFocusable = isSelectable
            isClickable = isSelectable
            setOnClickListener { view -> clickListener?.let { it(view) } }
        }
        button?.isEnabled = isEnabled
        button?.icon = icon
    }

    /**
     * Set a listener for button click
     */
    fun setOnClickListener(listener: (View) -> Unit) {
        clickListener = listener
    }
}
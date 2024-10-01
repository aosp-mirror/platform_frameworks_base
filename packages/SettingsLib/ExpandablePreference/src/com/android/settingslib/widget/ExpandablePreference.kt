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
import android.util.AttributeSet
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import com.android.settingslib.widget.preference.expandable.R

class ExpandablePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : PreferenceGroup(context, attrs, defStyleAttr, defStyleRes), Expandable {

    private var isExpanded = false
    private var expandIcon: ImageView? = null
    private var isDirty = true // Flag to track changes

    init {
        layoutResource = com.android.settingslib.widget.theme.R.layout.settingslib_expressive_preference
        widgetLayoutResource = R.layout.settingslib_widget_expandable_icon
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false

        expandIcon = holder.findViewById(R.id.expand_icon) as ImageView?

        updateExpandedState()

        holder.itemView.setOnClickListener { toggleExpansion() }
    }

    override fun addPreference(preference: Preference): Boolean {
        preference.isVisible = isExpanded
        return super.addPreference(preference)
    }

    override fun onPrepareAddPreference(preference: Preference): Boolean {
        preference.isVisible = isExpanded
        return super.onPrepareAddPreference(preference)
    }

    override fun isExpanded(): Boolean {
        return isExpanded
    }

    private fun toggleExpansion() {
        isExpanded = !isExpanded
        isDirty = true // Mark as dirty when expansion state changes
        updateExpandedState()
        notifyChanged()
    }

    private fun updateExpandedState() {
        expandIcon?.rotation = when (isExpanded) {
            true -> ROTATION_EXPANDED
            false -> ROTATION_COLLAPSED
        }

        if (isDirty) {
            (0 until preferenceCount).forEach { i ->
                getPreference(i).isVisible = isExpanded
            }
            isDirty = false
        }
    }

    companion object {
        private const val ROTATION_EXPANDED = 180f
        private const val ROTATION_COLLAPSED = 0f
    }
}
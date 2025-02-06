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
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import com.android.settingslib.widget.preference.banner.R

/**
 * Custom PreferenceGroup that allows expanding and collapsing child preferences.
 */
class BannerMessagePreferenceGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : PreferenceGroup(context, attrs, defStyleAttr, defStyleRes), GroupSectionDividerMixin {

    private var isExpanded = false
    private var expandPreference: NumberButtonPreference? = null
    private var collapsePreference: SectionButtonPreference? = null
    private val childPreferences = mutableListOf<BannerMessagePreference>()
    private var expandKey: String? = null
    private var expandTitle: String? = null
    private var collapseKey: String? = null
    private var collapseTitle: String? = null
    private var collapseIcon: Drawable? = null
    var expandContentDescription: Int = 0
        set(value) {
            field = value
            expandPreference?.btnContentDescription = expandContentDescription
        }

    init {
        isPersistent = false // This group doesn't store data
        layoutResource = R.layout.settingslib_banner_message_preference_group

        initAttributes(context, attrs, defStyleAttr)
    }

    override fun addPreference(preference: Preference): Boolean {
        if (preference !is BannerMessagePreference) {
            return false
        }

        if (childPreferences.size >= MAX_CHILDREN) {
            return false
        }

        childPreferences.add(preference)
        expandPreference?.let {
            it.count = childPreferences.size - 1
        }
        updateExpandCollapsePreference()
        updateChildrenVisibility()
        return super.addPreference(preference)
    }

    override fun removePreference(preference: Preference): Boolean {
        if (preference !is BannerMessagePreference) {
            return false
        }
        childPreferences.remove(preference)
        expandPreference?.let {
            it.count = childPreferences.size - 1
        }
        updateChildrenVisibility()
        updateExpandCollapsePreference()
        return super.removePreference(preference)
    }

    override fun removePreferenceRecursively(key: CharSequence): Boolean {
        val preference = findPreference<Preference>(key) ?: return false

        if (preference !is BannerMessagePreference) {
            return false
        }

        childPreferences.remove(preference)
        expandPreference?.let {
            it.count = childPreferences.size - 1
        }
        updateChildrenVisibility()
        updateExpandCollapsePreference()
        return super.removePreferenceRecursively(key)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        if (childPreferences.size >= 2) {
            if (expandPreference == null) {
                expandPreference = NumberButtonPreference(context).apply {
                    key = expandKey
                    title = expandTitle
                    count = childPreferences.size - 1
                    btnContentDescription = expandContentDescription
                    order = EXPAND_ORDER
                    clickListener = View.OnClickListener {
                        toggleExpansion()
                    }
                }
                super.addPreference(expandPreference!!)
            }
            if (collapsePreference == null) {
                collapsePreference = SectionButtonPreference(context)
                    .apply {
                    key = collapseKey
                    title = collapseTitle
                    icon = collapseIcon
                    order = COLLAPSE_ORDER
                    setOnClickListener {
                        toggleExpansion()
                    }
                }
                super.addPreference(collapsePreference!!)
            }
        }
        updateExpandCollapsePreference()
        updateChildrenVisibility()
    }

    private fun updateExpandCollapsePreference() {
        expandPreference?.isVisible = !isExpanded && childPreferences.size > 1
        collapsePreference?.isVisible = isExpanded && childPreferences.size > 1
    }

    private fun updateChildrenVisibility() {
        for (i in 0 until childPreferences.size) {
            val child = childPreferences[i]
            if (i == 0) {
                // Make this explicitly visible when e.g. the first BannerMessagePreference
                // in the group is dismissed
                child.isVisible = true
            } else {
                child.isVisible = isExpanded
            }
        }
    }

    private fun toggleExpansion() {
        isExpanded = !isExpanded
        updateExpandCollapsePreference()
        updateChildrenVisibility()
    }

    private fun initAttributes(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        context.obtainStyledAttributes(
            attrs,
            R.styleable.BannerMessagePreferenceGroup, defStyleAttr, 0
        ).apply {
            expandKey = getString(R.styleable.BannerMessagePreferenceGroup_expandKey)
            expandTitle = getString(R.styleable.BannerMessagePreferenceGroup_expandTitle)
            collapseKey = getString(R.styleable.BannerMessagePreferenceGroup_collapseKey)
            collapseTitle = getString(R.styleable.BannerMessagePreferenceGroup_collapseTitle)
            collapseIcon = getDrawable(R.styleable.BannerMessagePreferenceGroup_collapseIcon)
            recycle()
        }
    }

    companion object {
        private const val MAX_CHILDREN = 3
        // Arbitrary large order numbers for the two preferences
        // needed to make sure any Banners are added above them
        private const val EXPAND_ORDER = 99
        private const val COLLAPSE_ORDER = 100
    }
}
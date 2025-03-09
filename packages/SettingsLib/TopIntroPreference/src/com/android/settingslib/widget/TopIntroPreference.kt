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
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RequiresApi
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.settingslib.widget.preference.topintro.R

open class TopIntroPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : Preference(context, attrs, defStyleAttr, defStyleRes), GroupSectionDividerMixin {

    private var isCollapsable: Boolean = false
    private var minLines: Int = 2
    private var hyperlinkListener: View.OnClickListener? = null
    private var learnMoreListener: View.OnClickListener? = null
    private var learnMoreText: CharSequence? = null

    init {
        if (SettingsThemeHelper.isExpressiveTheme(context)) {
            layoutResource = R.layout.settingslib_expressive_top_intro
            initAttributes(context, attrs, defStyleAttr)
        } else {
            layoutResource = R.layout.top_intro_preference
        }
        isSelectable = false
    }

    private fun initAttributes(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        context.obtainStyledAttributes(
            attrs,
            COLLAPSABLE_TEXT_VIEW_ATTRS, defStyleAttr, 0
        ).apply {
            isCollapsable = getBoolean(IS_COLLAPSABLE, false)
            minLines = getInt(
                MIN_LINES,
                if (isCollapsable) DEFAULT_MIN_LINES else DEFAULT_MAX_LINES
            ).coerceIn(1, DEFAULT_MAX_LINES)
            recycle()
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false

        if (!SettingsThemeHelper.isExpressiveTheme(context)) {
            return
        }

        (holder.findViewById(R.id.collapsable_text_view) as? CollapsableTextView)?.apply {
            setCollapsable(isCollapsable)
            setMinLines(minLines)
            visibility = if (title.isNullOrEmpty()) View.GONE else View.VISIBLE
            setText(title.toString())
            if (hyperlinkListener != null) {
                setHyperlinkListener(hyperlinkListener)
            }
            if (learnMoreListener != null) {
                setLearnMoreText(learnMoreText)
                setLearnMoreAction(learnMoreListener)
            }
        }
    }

    /**
     * Sets whether the text view is collapsable.
     * @param collapsable True if the text view should be collapsable, false otherwise.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun setCollapsable(collapsable: Boolean) {
        isCollapsable = collapsable
        notifyChanged()
    }

    /**
     * Sets the minimum number of lines to display when collapsed.
     * @param lines The minimum number of lines.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun setMinLines(lines: Int) {
        minLines = lines.coerceIn(1, DEFAULT_MAX_LINES)
        notifyChanged()
    }

    /**
     * Sets the action when clicking on the hyperlink in the text.
     * @param listener The click listener for hyperlink.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun setHyperlinkListener(listener: View.OnClickListener) {
        if (hyperlinkListener != listener) {
            hyperlinkListener = listener
            notifyChanged()
        }
    }

    /**
     * Sets the action when clicking on the learn more view.
     * @param listener The click listener for learn more.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun setLearnMoreAction(listener: View.OnClickListener) {
        if (learnMoreListener != listener) {
            learnMoreListener = listener
            notifyChanged()
        }
    }

    /**
     * Sets the text of learn more view.
     * @param text The text of learn more.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun setLearnMoreText(text: CharSequence) {
        if (!TextUtils.equals(learnMoreText, text)) {
            learnMoreText = text
            notifyChanged()
        }
    }

    companion object {
        private const val DEFAULT_MAX_LINES = 10
        private const val DEFAULT_MIN_LINES = 2

        private val COLLAPSABLE_TEXT_VIEW_ATTRS =
            com.android.settingslib.widget.theme.R.styleable.CollapsableTextView
        private val MIN_LINES =
            com.android.settingslib.widget.theme.R.styleable.CollapsableTextView_android_minLines
        private val IS_COLLAPSABLE =
            com.android.settingslib.widget.theme.R.styleable.CollapsableTextView_isCollapsable
    }
}

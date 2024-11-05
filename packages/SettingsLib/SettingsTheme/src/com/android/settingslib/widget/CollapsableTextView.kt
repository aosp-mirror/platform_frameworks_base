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
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.settingslib.widget.theme.R
import com.google.android.material.button.MaterialButton

class CollapsableTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private var isCollapsable: Boolean = false
    private var isCollapsed: Boolean = false
    private var minLines: Int = DEFAULT_MIN_LINES

    private val titleTextView: TextView
    private val collapseButton: MaterialButton
    private val collapseButtonResources: CollapseButtonResources
    private var hyperlinkListener: View.OnClickListener? = null
    private var learnMoreListener: View.OnClickListener? = null
    private var learnMoreText: CharSequence? = null
    private var learnMoreSpan: LearnMoreSpan? = null
    val learnMoreTextView: LinkableTextView
    var isLearnMoreEnabled: Boolean = false

    init {
        LayoutInflater.from(context)
            .inflate(R.layout.settingslib_expressive_collapsable_textview, this)
        titleTextView = findViewById(android.R.id.title)
        collapseButton = findViewById(R.id.collapse_button)
        learnMoreTextView = findViewById(R.id.settingslib_expressive_learn_more)

        collapseButtonResources = CollapseButtonResources(
            context.getDrawable(R.drawable.settingslib_expressive_icon_collapse)!!,
            context.getDrawable(R.drawable.settingslib_expressive_icon_expand)!!,
            context.getString(R.string.settingslib_expressive_text_collapse),
            context.getString(R.string.settingslib_expressive_text_expand)
        )

        collapseButton.setOnClickListener {
            isCollapsed = !isCollapsed
            updateView()
        }

        initAttributes(context, attrs, defStyleAttr)
    }

    private fun initAttributes(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        context.obtainStyledAttributes(
            attrs, Attrs, defStyleAttr, 0
        ).apply {
            val gravity = getInt(GravityAttr, Gravity.START)
            when (gravity) {
                Gravity.CENTER_VERTICAL, Gravity.CENTER, Gravity.CENTER_HORIZONTAL -> {
                    centerHorizontally(titleTextView)
                    centerHorizontally(collapseButton)
                }
            }
            recycle()
        }
    }

    private fun centerHorizontally(view: View) {
        (view.layoutParams as LayoutParams).apply {
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
            horizontalBias = 0.5f
        }
    }

    /**
     * Sets the text content of the CollapsableTextView.
     * @param text The text to display.
     */
    fun setText(text: String) {
        titleTextView.text = text
    }

    /**
     * Sets whether the text view is collapsable.
     * @param collapsable True if the text view should be collapsable, false otherwise.
     */
    fun setCollapsable(collapsable: Boolean) {
        isCollapsable = collapsable
        updateView()
    }

    /**
     * Sets the minimum number of lines to display when collapsed.
     * @param lines The minimum number of lines.
     */
    fun setMinLines(line: Int) {
        minLines = line.coerceIn(1, DEFAULT_MAX_LINES)
        updateView()
    }

    /**
     * Sets the action when clicking on the learn more view.
     * @param listener The click listener for learn more.
     */
    fun setLearnMoreAction(listener: View.OnClickListener?) {
        if (learnMoreListener != listener) {
            learnMoreListener = listener
            formatLearnMoreText()
        }
    }

    /**
     * Sets the text of learn more view.
     * @param text The text of learn more.
     */
    fun setLearnMoreText(text: CharSequence?) {
        if (!TextUtils.equals(learnMoreText, text)) {
            learnMoreText = text
            formatLearnMoreText()
        }
    }

    fun setHyperlinkListener(listener: View.OnClickListener?) {
        if (hyperlinkListener != listener) {
            hyperlinkListener = listener
            linkifyTitle()
        }
    }

    private fun linkifyTitle() {
        var text = titleTextView.text.toString()
        val beginIndex = text.indexOf(LINK_BEGIN_MARKER)
        text = text.replace(LINK_BEGIN_MARKER, "")
        val endIndex = text.indexOf(LINK_END_MARKER)
        text = text.replace(LINK_END_MARKER, "")
        titleTextView.text = text
        if (beginIndex == -1 || endIndex == -1 || beginIndex >= endIndex) {
            return
        }

        titleTextView.setText(text, TextView.BufferType.SPANNABLE)
        titleTextView.movementMethod = LinkMovementMethod.getInstance()
        val spannableContent = titleTextView.getText() as Spannable
        val spannableLink = object : ClickableSpan() {
            override fun onClick(widget: View) {
                hyperlinkListener?.onClick(widget)
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = true
            }
        }
        spannableContent.setSpan(
            spannableLink,
            beginIndex,
            endIndex,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun formatLearnMoreText() {
        if (learnMoreListener == null || TextUtils.isEmpty(learnMoreText)) {
            learnMoreTextView.visibility = GONE
            isLearnMoreEnabled = false
            return
        }
        val spannableLearnMoreText = SpannableString(learnMoreText)
        if (learnMoreSpan != null) {
            spannableLearnMoreText.removeSpan(learnMoreSpan)
        }
        learnMoreSpan = LearnMoreSpan(clickListener = learnMoreListener!!)
        spannableLearnMoreText.setSpan(learnMoreSpan, 0, learnMoreText!!.length, 0)
        learnMoreTextView.setText(spannableLearnMoreText)
        learnMoreTextView.visibility = VISIBLE
        isLearnMoreEnabled = true
    }

    private fun updateView() {
        when {
            isCollapsed -> {
                collapseButton.apply {
                    text = collapseButtonResources.expandText
                    icon = collapseButtonResources.expandIcon
                }
                titleTextView.maxLines = minLines
            }

            else -> {
                collapseButton.apply {
                    text = collapseButtonResources.collapseText
                    icon = collapseButtonResources.collapseIcon
                }
                titleTextView.maxLines = DEFAULT_MAX_LINES
            }
        }
        collapseButton.visibility = if (isCollapsable) VISIBLE else GONE
        learnMoreTextView.visibility = if (isLearnMoreEnabled && !isCollapsed) VISIBLE else GONE
    }

    private data class CollapseButtonResources(
        val collapseIcon: Drawable,
        val expandIcon: Drawable,
        val collapseText: String,
        val expandText: String
    )

    companion object {
        private const val DEFAULT_MAX_LINES = 10
        private const val DEFAULT_MIN_LINES = 2

        private const val LINK_BEGIN_MARKER = "LINK_BEGIN"
        private const val LINK_END_MARKER = "LINK_END"

        private val Attrs = R.styleable.CollapsableTextView
        private val GravityAttr = R.styleable.CollapsableTextView_android_gravity
    }
}

internal class LearnMoreSpan(
    val url: String = "",
    val clickListener: View.OnClickListener) : URLSpan(url) {
    override fun onClick(widget: View) {
        clickListener.onClick(widget)
    }
}

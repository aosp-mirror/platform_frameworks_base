/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.systemui.bubbles

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.android.internal.util.ContrastColorUtil
import com.android.systemui.Interpolators
import com.android.systemui.R

/**
 * Educational view to highlight the manage button that allows a user to configure the settings
 * for the bubble. Shown only the first time a user expands a bubble.
 */
class ManageEducationView @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayout(context, attrs, defStyleAttr, defStyleRes) {

    private val manageView by lazy { findViewById<View>(R.id.manage_education_view) }
    private val manageButton by lazy { findViewById<Button>(R.id.manage) }
    private val gotItButton by lazy { findViewById<Button>(R.id.got_it) }
    private val titleTextView by lazy { findViewById<TextView>(R.id.user_education_title) }
    private val descTextView by lazy { findViewById<TextView>(R.id.user_education_description) }
    private var isInflated = false

    init {
        this.visibility = View.GONE
        this.elevation = resources.getDimensionPixelSize(R.dimen.bubble_elevation).toFloat()
        this.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }

    override fun setLayoutDirection(direction: Int) {
        super.setLayoutDirection(direction)
        // setLayoutDirection runs before onFinishInflate
        // so skip if views haven't inflated; otherwise we'll get NPEs
        if (!isInflated) return
        setDirection()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        isInflated = true
        setDirection()
        setTextColor()
    }

    private fun setTextColor() {
        val typedArray = mContext.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent,
            android.R.attr.textColorPrimaryInverse))
        val bgColor = typedArray.getColor(0 /* index */, Color.BLACK)
        var textColor = typedArray.getColor(1 /* index */, Color.WHITE)
        typedArray.recycle()
        textColor = ContrastColorUtil.ensureTextContrast(textColor, bgColor, true)
        titleTextView.setTextColor(textColor)
        descTextView.setTextColor(textColor)
    }

    fun setDirection() {
        manageView.setBackgroundResource(
            if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                R.drawable.bubble_stack_user_education_bg_rtl
            else R.drawable.bubble_stack_user_education_bg)
        titleTextView.gravity = Gravity.START
        descTextView.gravity = Gravity.START
    }

    fun show(expandedView: BubbleExpandedView, rect : Rect, hideMenu: Runnable) {
        alpha = 0f
        visibility = View.VISIBLE
        post {
            expandedView.getManageButtonBoundsOnScreen(rect)
            with(hideMenu) {
                manageButton
                    .setOnClickListener {
                        expandedView.findViewById<View>(R.id.settings_button).performClick()
                        this.run()
                    }
                gotItButton.setOnClickListener { this.run() }
                setOnClickListener { this.run() }
            }
            with(manageView) {
                translationX = 0f
                val inset = resources.getDimensionPixelSize(
                    R.dimen.bubbles_manage_education_top_inset)
                translationY = (rect.top - manageView.height + inset).toFloat()
            }
            bringToFront()
            animate()
                .setDuration(BubbleStackView.ANIMATE_STACK_USER_EDUCATION_DURATION.toLong())
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(1f)
        }
    }
}
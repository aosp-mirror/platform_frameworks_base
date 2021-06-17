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
package com.android.wm.shell.bubbles

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.android.internal.util.ContrastColorUtil
import com.android.wm.shell.R
import com.android.wm.shell.animation.Interpolators

/**
 * User education view to highlight the manage button that allows a user to configure the settings
 * for the bubble. Shown only the first time a user expands a bubble.
 */
class ManageEducationView constructor(context: Context) : LinearLayout(context) {

    private val TAG = if (BubbleDebugConfig.TAG_WITH_CLASS_NAME) "BubbleManageEducationView"
        else BubbleDebugConfig.TAG_BUBBLES

    private val ANIMATE_DURATION: Long = 200
    private val ANIMATE_DURATION_SHORT: Long = 40

    private val manageView by lazy { findViewById<View>(R.id.manage_education_view) }
    private val manageButton by lazy { findViewById<Button>(R.id.manage) }
    private val gotItButton by lazy { findViewById<Button>(R.id.got_it) }
    private val titleTextView by lazy { findViewById<TextView>(R.id.user_education_title) }
    private val descTextView by lazy { findViewById<TextView>(R.id.user_education_description) }

    private var isHiding = false

    init {
        LayoutInflater.from(context).inflate(R.layout.bubbles_manage_button_education, this)
        visibility = View.GONE
        elevation = resources.getDimensionPixelSize(R.dimen.bubble_elevation).toFloat()

        // BubbleStackView forces LTR by default
        // since most of Bubble UI direction depends on positioning by the user.
        // This view actually lays out differently in RTL, so we set layout LOCALE here.
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }

    override fun setLayoutDirection(layoutDirection: Int) {
        super.setLayoutDirection(layoutDirection)
        setDrawableDirection()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        layoutDirection = resources.configuration.layoutDirection
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

    private fun setDrawableDirection() {
        manageView.setBackgroundResource(
            if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                R.drawable.bubble_stack_user_education_bg_rtl
            else R.drawable.bubble_stack_user_education_bg)
    }

    /**
     * If necessary, toggles the user education view for the manage button. This is shown when the
     * bubble stack is expanded for the first time.
     *
     * @param show whether the user education view should show or not.
     */
    fun show(expandedView: BubbleExpandedView, rect: Rect) {
        if (visibility == VISIBLE) return

        alpha = 0f
        visibility = View.VISIBLE
        post {
            expandedView.getManageButtonBoundsOnScreen(rect)

            manageButton
                .setOnClickListener {
                    expandedView.findViewById<View>(R.id.settings_button).performClick()
                    hide(true /* isStackExpanding */)
                }
            gotItButton.setOnClickListener { hide(true /* isStackExpanding */) }
            setOnClickListener { hide(true /* isStackExpanding */) }

            with(manageView) {
                translationX = 0f
                val inset = resources.getDimensionPixelSize(
                    R.dimen.bubbles_manage_education_top_inset)
                translationY = (rect.top - manageView.height + inset).toFloat()
            }
            bringToFront()
            animate()
                .setDuration(ANIMATE_DURATION)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(1f)
        }
        setShouldShow(false)
    }

    fun hide(isStackExpanding: Boolean) {
        if (visibility != VISIBLE || isHiding) return

        animate()
            .withStartAction { isHiding = true }
            .alpha(0f)
            .setDuration(if (isStackExpanding) ANIMATE_DURATION_SHORT else ANIMATE_DURATION)
            .withEndAction {
                isHiding = false
                visibility = GONE
            }
    }

    private fun setShouldShow(shouldShow: Boolean) {
        context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_MANAGED_EDUCATION, !shouldShow).apply()
    }
}

const val PREF_MANAGED_EDUCATION: String = "HasSeenBubblesManageOnboarding"
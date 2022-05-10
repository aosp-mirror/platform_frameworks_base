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
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.android.internal.R.color.system_neutral1_900
import com.android.wm.shell.R
import com.android.wm.shell.animation.Interpolators

/**
 * User education view to highlight the manage button that allows a user to configure the settings
 * for the bubble. Shown only the first time a user expands a bubble.
 */
class ManageEducationView constructor(context: Context, positioner: BubblePositioner)
    : LinearLayout(context) {

    private val TAG = if (BubbleDebugConfig.TAG_WITH_CLASS_NAME) "ManageEducationView"
        else BubbleDebugConfig.TAG_BUBBLES

    private val ANIMATE_DURATION: Long = 200

    private val positioner: BubblePositioner = positioner
    private val manageView by lazy { findViewById<ViewGroup>(R.id.manage_education_view) }
    private val manageButton by lazy { findViewById<Button>(R.id.manage_button) }
    private val gotItButton by lazy { findViewById<Button>(R.id.got_it) }

    private var isHiding = false
    private var realManageButtonRect = Rect()
    private var bubbleExpandedView: BubbleExpandedView? = null

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
    }

    private fun setButtonColor() {
        val typedArray = mContext.obtainStyledAttributes(intArrayOf(
                com.android.internal.R.attr.colorAccentPrimary))
        val buttonColor = typedArray.getColor(0 /* index */, Color.TRANSPARENT)
        typedArray.recycle()

        manageButton.setTextColor(mContext.getColor(system_neutral1_900))
        manageButton.setBackgroundDrawable(ColorDrawable(buttonColor))
        gotItButton.setBackgroundDrawable(ColorDrawable(buttonColor))
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
     * @param expandedView the expandedView the user education is shown on top of.
     */
    fun show(expandedView: BubbleExpandedView) {
        setButtonColor()
        if (visibility == VISIBLE) return

        bubbleExpandedView = expandedView
        expandedView.taskView?.setObscuredTouchRect(Rect(positioner.screenRect))

        layoutParams.width = if (positioner.isLargeScreen || positioner.isLandscape)
            context.resources.getDimensionPixelSize(R.dimen.bubbles_user_education_width)
        else ViewGroup.LayoutParams.MATCH_PARENT

        alpha = 0f
        visibility = View.VISIBLE
        expandedView.getManageButtonBoundsOnScreen(realManageButtonRect)
        manageView.setPadding(realManageButtonRect.left - expandedView.manageButtonMargin,
                manageView.paddingTop, manageView.paddingRight, manageView.paddingBottom)
        post {
            manageButton
                .setOnClickListener {
                    hide()
                    expandedView.findViewById<View>(R.id.manage_button).performClick()
                }
            gotItButton.setOnClickListener { hide() }
            setOnClickListener { hide() }

            val offsetViewBounds = Rect()
            manageButton.getDrawingRect(offsetViewBounds)
            manageView.offsetDescendantRectToMyCoords(manageButton, offsetViewBounds)
            translationX = 0f
            translationY = (realManageButtonRect.top - offsetViewBounds.top).toFloat()
            bringToFront()
            animate()
                .setDuration(ANIMATE_DURATION)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(1f)
        }
        setShouldShow(false)
    }

    fun hide() {
        bubbleExpandedView?.taskView?.setObscuredTouchRect(null)
        if (visibility != VISIBLE || isHiding) return

        animate()
            .withStartAction { isHiding = true }
            .alpha(0f)
            .setDuration(ANIMATE_DURATION)
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
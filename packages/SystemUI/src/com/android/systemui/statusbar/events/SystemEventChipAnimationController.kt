/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.events

import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout

import com.android.systemui.R
import com.android.systemui.statusbar.SuperStatusBarViewFactory
import com.android.systemui.statusbar.phone.StatusBarLocationPublisher
import com.android.systemui.statusbar.phone.StatusBarWindowController
import com.android.systemui.statusbar.phone.StatusBarWindowView

import javax.inject.Inject

/**
 * Controls the view for system event animations.
 */
class SystemEventChipAnimationController @Inject constructor(
    private val context: Context,
    private val statusBarViewFactory: SuperStatusBarViewFactory,
    private val statusBarWindowController: StatusBarWindowController,
    private val locationPublisher: StatusBarLocationPublisher
) : SystemStatusChipAnimationCallback {
    var showPersistentDot = false
        set(value) {
            field = value
            statusBarWindowController.setForceStatusBarVisible(value)
            maybeUpdateShowDot()
        }

    private lateinit var animationWindowView: FrameLayout
    private lateinit var animationDotView: View
    private lateinit var statusBarWindowView: StatusBarWindowView
    private var currentAnimatedView: View? = null

    // TODO: move to dagger
    private var initialized = false

    override fun onChipAnimationStart(
        viewCreator: (context: Context) -> View,
        @SystemAnimationState state: Int
    ) {
        if (!initialized) init()

        if (state == ANIMATING_IN) {
            currentAnimatedView = viewCreator(context)
            animationWindowView.addView(currentAnimatedView, layoutParamsDefault())

            // We are animating IN; chip comes in from View.END
            currentAnimatedView?.apply {
                val translation = width.toFloat()
                translationX = if (isLayoutRtl) -translation else translation
                alpha = 0f
                visibility = View.VISIBLE
                setPadding(locationPublisher.marginLeft, 0, locationPublisher.marginRight, 0)
            }
        } else {
            // We are animating away
            currentAnimatedView?.apply {
                translationX = 0f
                alpha = 1f
            }
        }
    }

    override fun onChipAnimationEnd(@SystemAnimationState state: Int) {
        if (state == ANIMATING_IN) {
            // Finished animating in
            currentAnimatedView?.apply {
                translationX = 0f
                alpha = 1f
            }
        } else {
            // Finished animating away
            currentAnimatedView?.apply {
                visibility = View.INVISIBLE
            }
            animationWindowView.removeView(currentAnimatedView)
        }
    }

    override fun onChipAnimationUpdate(
        animator: ValueAnimator,
        @SystemAnimationState state: Int
    ) {
        // Alpha is parameterized 0,1, and translation from (width, 0)
        currentAnimatedView?.apply {
            val amt = animator.animatedValue as Float

            alpha = amt

            val w = width
            val translation = (1 - amt) * w
            translationX = if (isLayoutRtl) -translation else translation
        }
    }

    private fun maybeUpdateShowDot() {
        if (!initialized) return
        if (!showPersistentDot && currentAnimatedView == null) {
            animationDotView.visibility = View.INVISIBLE
        }
    }

    private fun init() {
        initialized = true
        statusBarWindowView = statusBarViewFactory.statusBarWindowView
        animationWindowView = LayoutInflater.from(context)
                .inflate(R.layout.system_event_animation_window, null) as FrameLayout
        animationDotView = animationWindowView.findViewById(R.id.dot_view)
        val lp = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        statusBarWindowView.addView(animationWindowView, lp)
    }

    private fun start() = if (animationWindowView.isLayoutRtl) right() else left()
    private fun right() = locationPublisher.marginRight
    private fun left() = locationPublisher.marginLeft

    private fun layoutParamsDefault(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also {
            it.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            it.marginStart = start()
    }
}

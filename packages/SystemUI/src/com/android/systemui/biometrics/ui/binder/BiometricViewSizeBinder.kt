/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.biometrics.ui.binder

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.Rect
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.Guideline
import androidx.core.animation.addListener
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.ui.viewmodel.PromptPosition
import com.android.systemui.biometrics.ui.viewmodel.PromptSize
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel
import com.android.systemui.biometrics.ui.viewmodel.isLarge
import com.android.systemui.biometrics.ui.viewmodel.isLeft
import com.android.systemui.biometrics.ui.viewmodel.isMedium
import com.android.systemui.biometrics.ui.viewmodel.isSmall
import com.android.systemui.biometrics.ui.viewmodel.isTop
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import kotlin.math.abs
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Helper for [BiometricViewBinder] to handle resize transitions. */
object BiometricViewSizeBinder {

    private const val ANIMATE_SMALL_TO_MEDIUM_DURATION_MS = 150
    // TODO(b/201510778): make private when related misuse is fixed
    const val ANIMATE_MEDIUM_TO_LARGE_DURATION_MS = 450

    /** Resizes [BiometricPromptLayout] and the [panelViewController] via the [PromptViewModel]. */
    fun bind(
        view: View,
        viewModel: PromptViewModel,
        viewsToHideWhenSmall: List<View>,
        jankListener: BiometricJankListener,
    ) {
        val windowManager = requireNotNull(view.context.getSystemService(WindowManager::class.java))
        val accessibilityManager =
            requireNotNull(view.context.getSystemService(AccessibilityManager::class.java))

        fun notifyAccessibilityChanged() {
            Utils.notifyAccessibilityContentChanged(accessibilityManager, view as ViewGroup)
        }

        fun startMonitoredAnimation(animators: List<Animator>) {
            with(AnimatorSet()) {
                addListener(jankListener)
                addListener(onEnd = { notifyAccessibilityChanged() })
                play(animators.first()).apply { animators.drop(1).forEach { next -> with(next) } }
                start()
            }
        }

        val leftGuideline = view.requireViewById<Guideline>(R.id.leftGuideline)
        val topGuideline = view.requireViewById<Guideline>(R.id.topGuideline)
        val rightGuideline = view.requireViewById<Guideline>(R.id.rightGuideline)
        val midGuideline = view.findViewById<Guideline>(R.id.midGuideline)

        val iconHolderView = view.requireViewById<View>(R.id.biometric_icon)
        val panelView = view.requireViewById<View>(R.id.panel)
        val cornerRadius = view.resources.getDimension(R.dimen.biometric_dialog_corner_size)
        val pxToDp =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                1f,
                view.resources.displayMetrics
            )
        val cornerRadiusPx = (pxToDp * cornerRadius).toInt()

        var currentSize: PromptSize? = null
        var currentPosition: PromptPosition = PromptPosition.Bottom
        panelView.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    when (currentPosition) {
                        PromptPosition.Right -> {
                            outline.setRoundRect(
                                0,
                                0,
                                view.width + cornerRadiusPx,
                                view.height,
                                cornerRadiusPx.toFloat()
                            )
                        }
                        PromptPosition.Left -> {
                            outline.setRoundRect(
                                -cornerRadiusPx,
                                0,
                                view.width,
                                view.height,
                                cornerRadiusPx.toFloat()
                            )
                        }
                        PromptPosition.Bottom,
                        PromptPosition.Top -> {
                            outline.setRoundRect(
                                0,
                                0,
                                view.width,
                                view.height + cornerRadiusPx,
                                cornerRadiusPx.toFloat()
                            )
                        }
                    }
                }
            }

        // ConstraintSets for animating between prompt sizes
        val mediumConstraintSet = ConstraintSet()
        mediumConstraintSet.clone(view as ConstraintLayout)

        val smallConstraintSet = ConstraintSet()
        smallConstraintSet.clone(mediumConstraintSet)

        val largeConstraintSet = ConstraintSet()
        largeConstraintSet.clone(mediumConstraintSet)
        largeConstraintSet.constrainMaxWidth(R.id.panel, 0)
        largeConstraintSet.setGuidelineBegin(R.id.leftGuideline, 0)
        largeConstraintSet.setGuidelineEnd(R.id.rightGuideline, 0)

        // TODO: Investigate better way to handle 180 rotations
        val flipConstraintSet = ConstraintSet()

        view.doOnLayout {
            fun setVisibilities(hideSensorIcon: Boolean, size: PromptSize) {
                viewsToHideWhenSmall.forEach { it.showContentOrHide(forceHide = size.isSmall) }
                largeConstraintSet.setVisibility(iconHolderView.id, View.GONE)
                largeConstraintSet.setVisibility(R.id.biometric_icon_overlay, View.GONE)
                largeConstraintSet.setVisibility(R.id.indicator, View.GONE)
                largeConstraintSet.setVisibility(R.id.scrollView, View.GONE)

                if (hideSensorIcon) {
                    smallConstraintSet.setVisibility(iconHolderView.id, View.GONE)
                    smallConstraintSet.setVisibility(R.id.biometric_icon_overlay, View.GONE)
                    smallConstraintSet.setVisibility(R.id.indicator, View.GONE)
                    mediumConstraintSet.setVisibility(iconHolderView.id, View.GONE)
                    mediumConstraintSet.setVisibility(R.id.biometric_icon_overlay, View.GONE)
                    mediumConstraintSet.setVisibility(R.id.indicator, View.GONE)
                }
            }

            view.repeatWhenAttached {
                lifecycleScope.launch {
                    viewModel.iconPosition.collect { position ->
                        if (position != Rect()) {
                            val iconParams =
                                iconHolderView.layoutParams as ConstraintLayout.LayoutParams

                            if (position.left != 0) {
                                iconParams.endToEnd = ConstraintSet.UNSET
                                iconParams.leftMargin = position.left
                                mediumConstraintSet.clear(R.id.biometric_icon, ConstraintSet.RIGHT)
                                mediumConstraintSet.connect(
                                    R.id.biometric_icon,
                                    ConstraintSet.LEFT,
                                    ConstraintSet.PARENT_ID,
                                    ConstraintSet.LEFT
                                )
                                mediumConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.LEFT,
                                    position.left
                                )
                                smallConstraintSet.clear(R.id.biometric_icon, ConstraintSet.RIGHT)
                                smallConstraintSet.connect(
                                    R.id.biometric_icon,
                                    ConstraintSet.LEFT,
                                    ConstraintSet.PARENT_ID,
                                    ConstraintSet.LEFT
                                )
                                smallConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.LEFT,
                                    position.left
                                )
                            }
                            if (position.top != 0) {
                                iconParams.bottomToBottom = ConstraintSet.UNSET
                                iconParams.topMargin = position.top
                                mediumConstraintSet.clear(R.id.biometric_icon, ConstraintSet.BOTTOM)
                                mediumConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.TOP,
                                    position.top
                                )
                                smallConstraintSet.clear(R.id.biometric_icon, ConstraintSet.BOTTOM)
                                smallConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.TOP,
                                    position.top
                                )
                            }
                            if (position.right != 0) {
                                iconParams.startToStart = ConstraintSet.UNSET
                                iconParams.rightMargin = position.right
                                mediumConstraintSet.clear(R.id.biometric_icon, ConstraintSet.LEFT)
                                mediumConstraintSet.connect(
                                    R.id.biometric_icon,
                                    ConstraintSet.RIGHT,
                                    ConstraintSet.PARENT_ID,
                                    ConstraintSet.RIGHT
                                )
                                mediumConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.RIGHT,
                                    position.right
                                )
                                smallConstraintSet.clear(R.id.biometric_icon, ConstraintSet.LEFT)
                                smallConstraintSet.connect(
                                    R.id.biometric_icon,
                                    ConstraintSet.RIGHT,
                                    ConstraintSet.PARENT_ID,
                                    ConstraintSet.RIGHT
                                )
                                smallConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.RIGHT,
                                    position.right
                                )
                            }
                            if (position.bottom != 0) {
                                iconParams.topToTop = ConstraintSet.UNSET
                                iconParams.bottomMargin = position.bottom
                                mediumConstraintSet.clear(R.id.biometric_icon, ConstraintSet.TOP)
                                mediumConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.BOTTOM,
                                    position.bottom
                                )
                                smallConstraintSet.clear(R.id.biometric_icon, ConstraintSet.TOP)
                                smallConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.BOTTOM,
                                    position.bottom
                                )
                            }
                            iconHolderView.layoutParams = iconParams
                        }
                    }
                }

                lifecycleScope.launch {
                    viewModel.iconSize.collect { iconSize ->
                        iconHolderView.layoutParams.width = iconSize.first
                        iconHolderView.layoutParams.height = iconSize.second
                        mediumConstraintSet.constrainWidth(R.id.biometric_icon, iconSize.first)
                        mediumConstraintSet.constrainHeight(R.id.biometric_icon, iconSize.second)
                    }
                }

                lifecycleScope.launch {
                    viewModel.guidelineBounds.collect { bounds ->
                        val bottomInset =
                            windowManager.maximumWindowMetrics.windowInsets
                                .getInsets(WindowInsets.Type.navigationBars())
                                .bottom
                        mediumConstraintSet.setGuidelineEnd(R.id.bottomGuideline, bottomInset)

                        if (bounds.left >= 0) {
                            mediumConstraintSet.setGuidelineBegin(leftGuideline.id, bounds.left)
                            smallConstraintSet.setGuidelineBegin(leftGuideline.id, bounds.left)
                        } else if (bounds.left < 0) {
                            mediumConstraintSet.setGuidelineEnd(leftGuideline.id, abs(bounds.left))
                            smallConstraintSet.setGuidelineEnd(leftGuideline.id, abs(bounds.left))
                        }

                        if (bounds.right >= 0) {
                            mediumConstraintSet.setGuidelineEnd(rightGuideline.id, bounds.right)
                            smallConstraintSet.setGuidelineEnd(rightGuideline.id, bounds.right)
                        } else if (bounds.right < 0) {
                            mediumConstraintSet.setGuidelineBegin(
                                rightGuideline.id,
                                abs(bounds.right)
                            )
                            smallConstraintSet.setGuidelineBegin(
                                rightGuideline.id,
                                abs(bounds.right)
                            )
                        }

                        if (bounds.top >= 0) {
                            mediumConstraintSet.setGuidelineBegin(topGuideline.id, bounds.top)
                            smallConstraintSet.setGuidelineBegin(topGuideline.id, bounds.top)
                        } else if (bounds.top < 0) {
                            mediumConstraintSet.setGuidelineEnd(topGuideline.id, abs(bounds.top))
                            smallConstraintSet.setGuidelineEnd(topGuideline.id, abs(bounds.top))
                        }

                        if (midGuideline != null) {
                            val left =
                                if (bounds.left >= 0) {
                                    abs(bounds.left)
                                } else {
                                    view.width - abs(bounds.left)
                                }
                            val right =
                                if (bounds.right >= 0) {
                                    view.width - abs(bounds.right)
                                } else {
                                    abs(bounds.right)
                                }
                            val mid = (left + right) / 2
                            mediumConstraintSet.setGuidelineBegin(midGuideline.id, mid)
                        }
                    }
                }

                lifecycleScope.launch {
                    combine(viewModel.hideSensorIcon, viewModel.size, ::Pair).collect {
                        (hideSensorIcon, size) ->
                        setVisibilities(hideSensorIcon, size)
                    }
                }

                lifecycleScope.launch {
                    combine(viewModel.position, viewModel.size, ::Pair).collect { (position, size)
                        ->
                        if (position.isLeft) {
                            if (size.isSmall) {
                                flipConstraintSet.clone(smallConstraintSet)
                            } else {
                                flipConstraintSet.clone(mediumConstraintSet)
                            }

                            // Move all content to other panel
                            flipConstraintSet.connect(
                                R.id.scrollView,
                                ConstraintSet.LEFT,
                                R.id.midGuideline,
                                ConstraintSet.LEFT
                            )
                            flipConstraintSet.connect(
                                R.id.scrollView,
                                ConstraintSet.RIGHT,
                                R.id.rightGuideline,
                                ConstraintSet.RIGHT
                            )
                        } else if (position.isTop) {
                            // Top position is only used for 180 rotation Udfps
                            // Requires repositioning due to sensor location at top of screen
                            mediumConstraintSet.connect(
                                R.id.scrollView,
                                ConstraintSet.TOP,
                                R.id.indicator,
                                ConstraintSet.BOTTOM
                            )
                            mediumConstraintSet.connect(
                                R.id.scrollView,
                                ConstraintSet.BOTTOM,
                                R.id.button_bar,
                                ConstraintSet.TOP
                            )
                            mediumConstraintSet.connect(
                                R.id.panel,
                                ConstraintSet.TOP,
                                R.id.biometric_icon,
                                ConstraintSet.TOP
                            )
                            mediumConstraintSet.setMargin(
                                R.id.panel,
                                ConstraintSet.TOP,
                                (-24 * pxToDp).toInt()
                            )
                            mediumConstraintSet.setVerticalBias(R.id.scrollView, 0f)
                        }

                        when {
                            size.isSmall -> {
                                if (position.isLeft) {
                                    flipConstraintSet.applyTo(view)
                                } else {
                                    smallConstraintSet.applyTo(view)
                                }
                            }
                            size.isMedium && currentSize.isSmall -> {
                                val autoTransition = AutoTransition()
                                autoTransition.setDuration(
                                    ANIMATE_SMALL_TO_MEDIUM_DURATION_MS.toLong()
                                )

                                if (position.isLeft) {
                                    flipConstraintSet.applyTo(view)
                                } else {
                                    mediumConstraintSet.applyTo(view)
                                }
                                TransitionManager.beginDelayedTransition(view, autoTransition)
                            }
                            size.isMedium -> {
                                if (position.isLeft) {
                                    flipConstraintSet.applyTo(view)
                                } else {
                                    mediumConstraintSet.applyTo(view)
                                }
                            }
                            size.isLarge -> {
                                val autoTransition = AutoTransition()
                                autoTransition.setDuration(
                                    if (currentSize.isSmall) {
                                        ANIMATE_SMALL_TO_MEDIUM_DURATION_MS.toLong()
                                    } else {
                                        ANIMATE_MEDIUM_TO_LARGE_DURATION_MS.toLong()
                                    }
                                )

                                largeConstraintSet.applyTo(view)
                                TransitionManager.beginDelayedTransition(view, autoTransition)
                            }
                        }

                        currentSize = size
                        currentPosition = position
                        notifyAccessibilityChanged()

                        panelView.invalidateOutline()
                        view.invalidate()
                        view.requestLayout()
                    }
                }
            }
        }
    }
}

private fun View.showContentOrHide(forceHide: Boolean = false) {
    val isTextViewWithBlankText = this is TextView && this.text.isBlank()
    val isImageViewWithoutImage = this is ImageView && this.drawable == null
    visibility =
        if (forceHide || isTextViewWithBlankText || isImageViewWithoutImage) {
            View.GONE
        } else {
            View.VISIBLE
        }
}

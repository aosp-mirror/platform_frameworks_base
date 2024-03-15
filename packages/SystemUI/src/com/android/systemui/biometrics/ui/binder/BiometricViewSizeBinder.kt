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
import androidx.core.view.doOnAttach
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import com.android.systemui.Flags.constraintBp
import com.android.systemui.biometrics.AuthPanelController
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.ui.viewmodel.PromptPosition
import com.android.systemui.biometrics.ui.viewmodel.PromptSize
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel
import com.android.systemui.biometrics.ui.viewmodel.isBottom
import com.android.systemui.biometrics.ui.viewmodel.isLarge
import com.android.systemui.biometrics.ui.viewmodel.isLeft
import com.android.systemui.biometrics.ui.viewmodel.isMedium
import com.android.systemui.biometrics.ui.viewmodel.isNullOrNotSmall
import com.android.systemui.biometrics.ui.viewmodel.isRight
import com.android.systemui.biometrics.ui.viewmodel.isSmall
import com.android.systemui.biometrics.ui.viewmodel.isTop
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import kotlin.math.min
import kotlinx.coroutines.flow.combine
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
        viewsToFadeInOnSizeChange: List<View>,
        panelViewController: AuthPanelController?,
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

        if (constraintBp()) {
            val leftGuideline = view.requireViewById<Guideline>(R.id.leftGuideline)
            val rightGuideline = view.requireViewById<Guideline>(R.id.rightGuideline)
            val bottomGuideline = view.requireViewById<Guideline>(R.id.bottomGuideline)
            val topGuideline = view.requireViewById<Guideline>(R.id.topGuideline)
            val midGuideline = view.findViewById<Guideline>(R.id.midGuideline)

            val iconHolderView = view.requireViewById<View>(R.id.biometric_icon)
            val panelView = view.requireViewById<View>(R.id.panel)
            val cornerRadius = view.resources.getDimension(R.dimen.biometric_dialog_corner_size)
            val cornerRadiusPx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        cornerRadius,
                        view.resources.displayMetrics
                    )
                    .toInt()

            // ConstraintSets for animating between prompt sizes
            val mediumConstraintSet = ConstraintSet()
            mediumConstraintSet.clone(view as ConstraintLayout)

            val smallConstraintSet = ConstraintSet()
            smallConstraintSet.clone(mediumConstraintSet)

            val largeConstraintSet = ConstraintSet()
            largeConstraintSet.clone(mediumConstraintSet)
            largeConstraintSet.setVisibility(iconHolderView.id, View.GONE)
            largeConstraintSet.setVisibility(R.id.biometric_icon_overlay, View.GONE)
            largeConstraintSet.setVisibility(R.id.indicator, View.GONE)
            largeConstraintSet.setVisibility(R.id.scrollView, View.GONE)

            // TODO: Investigate better way to handle 180 rotations
            val flipConstraintSet = ConstraintSet()
            flipConstraintSet.clone(mediumConstraintSet)
            flipConstraintSet.connect(
                R.id.scrollView,
                ConstraintSet.START,
                R.id.midGuideline,
                ConstraintSet.START
            )
            flipConstraintSet.connect(
                R.id.scrollView,
                ConstraintSet.END,
                R.id.rightGuideline,
                ConstraintSet.END
            )
            flipConstraintSet.setHorizontalBias(R.id.biometric_icon, .2f)

            // Round the panel outline
            panelView.outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(
                            0,
                            0,
                            view.width,
                            view.height + cornerRadiusPx,
                            cornerRadiusPx.toFloat()
                        )
                    }
                }

            view.doOnLayout {
                val windowBounds = windowManager.maximumWindowMetrics.bounds
                val bottomInset =
                    windowManager.maximumWindowMetrics.windowInsets
                        .getInsets(WindowInsets.Type.navigationBars())
                        .bottom

                // TODO: Move to viewmodel
                fun measureBounds(position: PromptPosition) {
                    val density = windowManager.currentWindowMetrics.density
                    val width = min((640 * density).toInt(), windowBounds.width())

                    var left = -1
                    var right = -1
                    var bottom = -1
                    var mid = -1

                    when {
                        position.isTop -> {
                            // Round bottom corners
                            panelView.outlineProvider =
                                object : ViewOutlineProvider() {
                                    override fun getOutline(view: View, outline: Outline) {
                                        outline.setRoundRect(
                                            0,
                                            -cornerRadiusPx,
                                            view.width,
                                            view.height,
                                            cornerRadiusPx.toFloat()
                                        )
                                    }
                                }
                            left = windowBounds.centerX() - width / 2 + viewModel.promptMargin
                            right = windowBounds.centerX() - width / 2 + viewModel.promptMargin
                            bottom = iconHolderView.centerY() * 2 - iconHolderView.centerY() / 4
                        }
                        position.isBottom -> {
                            // Round top corners
                            panelView.outlineProvider =
                                object : ViewOutlineProvider() {
                                    override fun getOutline(view: View, outline: Outline) {
                                        outline.setRoundRect(
                                            0,
                                            0,
                                            view.width,
                                            view.height + cornerRadiusPx,
                                            cornerRadiusPx.toFloat()
                                        )
                                    }
                                }

                            left = windowBounds.centerX() - width / 2
                            right = windowBounds.centerX() - width / 2
                            bottom = if (view.isLandscape()) bottomInset else 0
                        }
                        position.isLeft -> {
                            // Round right corners
                            panelView.outlineProvider =
                                object : ViewOutlineProvider() {
                                    override fun getOutline(view: View, outline: Outline) {
                                        outline.setRoundRect(
                                            -cornerRadiusPx,
                                            0,
                                            view.width,
                                            view.height,
                                            cornerRadiusPx.toFloat()
                                        )
                                    }
                                }

                            left = 0
                            mid = (windowBounds.width() * .85).toInt() / 2
                            right = windowBounds.width() - (windowBounds.width() * .85).toInt()
                            bottom = if (view.isLandscape()) bottomInset else 0
                        }
                        position.isRight -> {
                            // Round left corners
                            panelView.outlineProvider =
                                object : ViewOutlineProvider() {
                                    override fun getOutline(view: View, outline: Outline) {
                                        outline.setRoundRect(
                                            0,
                                            0,
                                            view.width + cornerRadiusPx,
                                            view.height,
                                            cornerRadiusPx.toFloat()
                                        )
                                    }
                                }

                            left = windowBounds.width() - (windowBounds.width() * .85).toInt()
                            right = 0
                            bottom = if (view.isLandscape()) bottomInset else 0
                            mid = windowBounds.width() - (windowBounds.width() * .85).toInt() / 2
                        }
                    }

                    val bounds = Rect(left, mid, right, bottom)
                    if (bounds.shouldAdjustLeftGuideline()) {
                        leftGuideline.setGuidelineBegin(bounds.left)
                        smallConstraintSet.setGuidelineBegin(leftGuideline.id, bounds.left)
                        mediumConstraintSet.setGuidelineBegin(leftGuideline.id, bounds.left)
                    }
                    if (bounds.shouldAdjustRightGuideline()) {
                        rightGuideline.setGuidelineEnd(bounds.right)
                        smallConstraintSet.setGuidelineEnd(rightGuideline.id, bounds.right)
                        mediumConstraintSet.setGuidelineEnd(rightGuideline.id, bounds.right)
                    }
                    if (bounds.shouldAdjustBottomGuideline()) {
                        bottomGuideline.setGuidelineEnd(bounds.bottom)
                        smallConstraintSet.setGuidelineEnd(bottomGuideline.id, bounds.bottom)
                        mediumConstraintSet.setGuidelineEnd(bottomGuideline.id, bounds.bottom)
                    }

                    if (position.isBottom) {
                        topGuideline.setGuidelinePercent(.25f)
                        mediumConstraintSet.setGuidelinePercent(topGuideline.id, .25f)
                    } else {
                        topGuideline.setGuidelinePercent(0f)
                        mediumConstraintSet.setGuidelinePercent(topGuideline.id, 0f)
                    }

                    if (mid != -1 && midGuideline != null) {
                        midGuideline.setGuidelineBegin(mid)
                    }
                }

                fun setVisibilities(size: PromptSize) {
                    viewsToHideWhenSmall.forEach { it.showContentOrHide(forceHide = size.isSmall) }

                    if (viewModel.showBpWithoutIconForCredential.value) {
                        smallConstraintSet.setVisibility(iconHolderView.id, View.GONE)
                        smallConstraintSet.setVisibility(R.id.biometric_icon_overlay, View.GONE)
                        smallConstraintSet.setVisibility(R.id.indicator, View.GONE)
                        mediumConstraintSet.setVisibility(iconHolderView.id, View.GONE)
                        mediumConstraintSet.setVisibility(R.id.biometric_icon_overlay, View.GONE)
                        mediumConstraintSet.setVisibility(R.id.indicator, View.GONE)
                    }
                }

                view.repeatWhenAttached {
                    var currentSize: PromptSize? = null

                    lifecycleScope.launch {
                        combine(viewModel.position, viewModel.size, ::Pair).collect {
                            (position, size) ->
                            view.doOnAttach {
                                if (position.isLeft) {
                                    flipConstraintSet.applyTo(view)
                                } else if (position.isRight) {
                                    mediumConstraintSet.applyTo(view)
                                }

                                measureBounds(position)
                                setVisibilities(size)
                                when {
                                    size.isSmall -> {
                                        val ratio =
                                            if (view.isLandscape()) {
                                                (windowBounds.height() -
                                                        bottomInset -
                                                        viewModel.promptMargin)
                                                    .toFloat() / windowBounds.height()
                                            } else {
                                                (windowBounds.height() - viewModel.promptMargin)
                                                    .toFloat() / windowBounds.height()
                                            }
                                        smallConstraintSet.setVerticalBias(iconHolderView.id, ratio)

                                        smallConstraintSet.applyTo(view as ConstraintLayout?)
                                    }
                                    size.isMedium && currentSize.isSmall -> {
                                        val autoTransition = AutoTransition()
                                        autoTransition.setDuration(
                                            ANIMATE_SMALL_TO_MEDIUM_DURATION_MS.toLong()
                                        )

                                        TransitionManager.beginDelayedTransition(
                                            view,
                                            autoTransition
                                        )
                                        mediumConstraintSet.applyTo(view)
                                    }
                                    size.isLarge -> {
                                        val autoTransition = AutoTransition()
                                        autoTransition.setDuration(
                                            ANIMATE_MEDIUM_TO_LARGE_DURATION_MS.toLong()
                                        )

                                        TransitionManager.beginDelayedTransition(
                                            view,
                                            autoTransition
                                        )
                                        largeConstraintSet.applyTo(view)
                                    }
                                }

                                currentSize = size
                                view.visibility = View.VISIBLE
                                viewModel.setIsIconViewLoaded(false)
                                notifyAccessibilityChanged()

                                view.invalidate()
                                view.requestLayout()
                            }
                        }
                    }
                }
            }
        } else if (panelViewController != null) {
            val iconHolderView = view.requireViewById<View>(R.id.biometric_icon_frame)
            val iconPadding = view.resources.getDimension(R.dimen.biometric_dialog_icon_padding)
            val fullSizeYOffset =
                view.resources.getDimension(
                    R.dimen.biometric_dialog_medium_to_large_translation_offset
                )

            // cache the original position of the icon view (as done in legacy view)
            // this must happen before any size changes can be made
            view.doOnLayout {
                // TODO(b/251476085): this old way of positioning has proven itself unreliable
                // remove this and associated thing like (UdfpsDialogMeasureAdapter) and
                // pin to the physical sensor
                val iconHolderOriginalY = iconHolderView.y

                // bind to prompt
                // TODO(b/251476085): migrate the legacy panel controller and simplify this
                view.repeatWhenAttached {
                    var currentSize: PromptSize? = null
                    lifecycleScope.launch {
                        /**
                         * View is only set visible in BiometricViewSizeBinder once PromptSize is
                         * determined that accounts for iconView size, to prevent prompt resizing
                         * being visible to the user.
                         *
                         * TODO(b/288175072): May be able to remove isIconViewLoaded once constraint
                         *   layout is implemented
                         */
                        combine(viewModel.isIconViewLoaded, viewModel.size, ::Pair).collect {
                            (isIconViewLoaded, size) ->
                            if (!isIconViewLoaded) {
                                return@collect
                            }

                            // prepare for animated size transitions
                            for (v in viewsToHideWhenSmall) {
                                v.showContentOrHide(forceHide = size.isSmall)
                            }

                            if (viewModel.showBpWithoutIconForCredential.value) {
                                iconHolderView.visibility = View.GONE
                            }

                            if (currentSize == null && size.isSmall) {
                                iconHolderView.alpha = 0f
                            }
                            if ((currentSize.isSmall && size.isMedium) || size.isSmall) {
                                viewsToFadeInOnSizeChange.forEach { it.alpha = 0f }
                            }

                            // TODO(b/302735104): Fix wrong height due to the delay of
                            // PromptContentView. addOnLayoutChangeListener() will cause crash
                            // when showing credential view, since |PromptIconViewModel| won't
                            // release the flow.
                            // propagate size changes to legacy panel controller and animate
                            // transitions
                            view.doOnLayout {
                                val width = view.measuredWidth
                                val height = view.measuredHeight

                                when {
                                    size.isSmall -> {
                                        iconHolderView.alpha = 1f
                                        val bottomInset =
                                            windowManager.maximumWindowMetrics.windowInsets
                                                .getInsets(WindowInsets.Type.navigationBars())
                                                .bottom
                                        iconHolderView.y =
                                            if (view.isLandscape()) {
                                                (view.height -
                                                    iconHolderView.height -
                                                    bottomInset) / 2f
                                            } else {
                                                view.height -
                                                    iconHolderView.height -
                                                    iconPadding -
                                                    bottomInset
                                            }
                                        val newHeight =
                                            iconHolderView.height + (2 * iconPadding.toInt()) -
                                                iconHolderView.paddingTop -
                                                iconHolderView.paddingBottom
                                        panelViewController.updateForContentDimensions(
                                            width,
                                            newHeight + bottomInset,
                                            0, /* animateDurationMs */
                                        )
                                    }
                                    size.isMedium && currentSize.isSmall -> {
                                        val duration = ANIMATE_SMALL_TO_MEDIUM_DURATION_MS
                                        panelViewController.updateForContentDimensions(
                                            width,
                                            height,
                                            duration,
                                        )
                                        startMonitoredAnimation(
                                            listOf(
                                                iconHolderView.asVerticalAnimator(
                                                    duration = duration.toLong(),
                                                    toY =
                                                        iconHolderOriginalY -
                                                            viewsToHideWhenSmall
                                                                .filter { it.isGone }
                                                                .sumOf { it.height },
                                                ),
                                                viewsToFadeInOnSizeChange.asFadeInAnimator(
                                                    duration = duration.toLong(),
                                                    delay = duration.toLong(),
                                                ),
                                            )
                                        )
                                    }
                                    size.isMedium && currentSize.isNullOrNotSmall -> {
                                        panelViewController.updateForContentDimensions(
                                            width,
                                            height,
                                            0, /* animateDurationMs */
                                        )
                                    }
                                    size.isLarge -> {
                                        val duration = ANIMATE_MEDIUM_TO_LARGE_DURATION_MS
                                        panelViewController.setUseFullScreen(true)
                                        panelViewController.updateForContentDimensions(
                                            panelViewController.containerWidth,
                                            panelViewController.containerHeight,
                                            duration,
                                        )

                                        startMonitoredAnimation(
                                            listOf(
                                                view.asVerticalAnimator(
                                                    duration.toLong() * 2 / 3,
                                                    toY = view.y - fullSizeYOffset
                                                ),
                                                listOf(view)
                                                    .asFadeInAnimator(
                                                        duration = duration.toLong() / 2,
                                                        delay = duration.toLong(),
                                                    ),
                                            )
                                        )
                                        // TODO(b/251476085): clean up (copied from legacy)
                                        if (view.isAttachedToWindow) {
                                            val parent = view.parent as? ViewGroup
                                            parent?.removeView(view)
                                        }
                                    }
                                }

                                currentSize = size
                                view.visibility = View.VISIBLE
                                viewModel.setIsIconViewLoaded(false)
                                notifyAccessibilityChanged()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun View.isLandscape(): Boolean {
    val r = context.display.rotation
    return if (
        context.resources.getBoolean(com.android.internal.R.bool.config_reverseDefaultRotation)
    ) {
        r == Surface.ROTATION_0 || r == Surface.ROTATION_180
    } else {
        r == Surface.ROTATION_90 || r == Surface.ROTATION_270
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

private fun View.centerX(): Int {
    return (x + width / 2).toInt()
}

private fun View.centerY(): Int {
    return (y + height / 2).toInt()
}

private fun Rect.shouldAdjustLeftGuideline(): Boolean = left != -1

private fun Rect.shouldAdjustRightGuideline(): Boolean = right != -1

private fun Rect.shouldAdjustBottomGuideline(): Boolean = bottom != -1

private fun View.asVerticalAnimator(
    duration: Long,
    toY: Float,
    fromY: Float = this.y
): ValueAnimator {
    val animator = ValueAnimator.ofFloat(fromY, toY)
    animator.duration = duration
    animator.addUpdateListener { y = it.animatedValue as Float }
    return animator
}

private fun List<View>.asFadeInAnimator(duration: Long, delay: Long): ValueAnimator {
    forEach { it.alpha = 0f }
    val animator = ValueAnimator.ofFloat(0f, 1f)
    animator.duration = duration
    animator.startDelay = delay
    animator.addUpdateListener {
        val alpha = it.animatedValue as Float
        forEach { view -> view.alpha = alpha }
    }
    return animator
}

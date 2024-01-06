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
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.core.animation.addListener
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import com.android.systemui.biometrics.AuthPanelController
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.ui.BiometricPromptLayout
import com.android.systemui.biometrics.ui.viewmodel.PromptSize
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel
import com.android.systemui.biometrics.ui.viewmodel.isLarge
import com.android.systemui.biometrics.ui.viewmodel.isMedium
import com.android.systemui.biometrics.ui.viewmodel.isNullOrNotSmall
import com.android.systemui.biometrics.ui.viewmodel.isSmall
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Helper for [BiometricViewBinder] to handle resize transitions. */
object BiometricViewSizeBinder {

    private const val ANIMATE_SMALL_TO_MEDIUM_DURATION_MS = 150
    // TODO(b/201510778): make private when related misuse is fixed
    const val ANIMATE_MEDIUM_TO_LARGE_DURATION_MS = 450

    /** Resizes [BiometricPromptLayout] and the [panelViewController] via the [PromptViewModel]. */
    fun bind(
        view: BiometricPromptLayout,
        viewModel: PromptViewModel,
        viewsToHideWhenSmall: List<TextView>,
        viewsToFadeInOnSizeChange: List<View>,
        panelViewController: AuthPanelController,
        jankListener: BiometricJankListener,
    ) {
        val windowManager = requireNotNull(view.context.getSystemService(WindowManager::class.java))
        val accessibilityManager =
            requireNotNull(view.context.getSystemService(AccessibilityManager::class.java))
        fun notifyAccessibilityChanged() {
            Utils.notifyAccessibilityContentChanged(accessibilityManager, view)
        }

        fun startMonitoredAnimation(animators: List<Animator>) {
            with(AnimatorSet()) {
                addListener(jankListener)
                addListener(onEnd = { notifyAccessibilityChanged() })
                play(animators.first()).apply { animators.drop(1).forEach { next -> with(next) } }
                start()
            }
        }

        val iconHolderView = view.requireViewById<View>(R.id.biometric_icon_frame)
        val iconPadding = view.resources.getDimension(R.dimen.biometric_dialog_icon_padding)
        val fullSizeYOffset =
            view.resources.getDimension(R.dimen.biometric_dialog_medium_to_large_translation_offset)

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
                     * determined that accounts for iconView size, to prevent prompt resizing being
                     * visible to the user.
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
                            v.showTextOrHide(forceHide = size.isSmall)
                        }
                        if (currentSize == null && size.isSmall) {
                            iconHolderView.alpha = 0f
                        }
                        if ((currentSize.isSmall && size.isMedium) || size.isSmall) {
                            viewsToFadeInOnSizeChange.forEach { it.alpha = 0f }
                        }

                        // propagate size changes to legacy panel controller and animate transitions
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
                                            (view.height - iconHolderView.height - bottomInset) / 2f
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

private fun View.isLandscape(): Boolean {
    val r = context.display?.rotation
    return r == Surface.ROTATION_90 || r == Surface.ROTATION_270
}

private fun TextView.showTextOrHide(forceHide: Boolean = false) {
    visibility = if (forceHide || text.isBlank()) View.GONE else View.VISIBLE
}

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

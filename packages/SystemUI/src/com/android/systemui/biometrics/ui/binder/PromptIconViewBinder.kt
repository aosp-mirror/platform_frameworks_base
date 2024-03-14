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
 *
 */

package com.android.systemui.biometrics.ui.binder

import android.graphics.Rect
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.LottieAnimationView
import com.android.settingslib.widget.LottieColorUtils
import com.android.systemui.Flags.constraintBp
import com.android.systemui.biometrics.ui.viewmodel.PromptIconViewModel
import com.android.systemui.biometrics.ui.viewmodel.PromptIconViewModel.AuthType
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.util.kotlin.Utils.Companion.toQuad
import com.android.systemui.util.kotlin.Utils.Companion.toQuint
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Sub-binder for [BiometricPromptLayout.iconView]. */
object PromptIconViewBinder {
    /**
     * Binds [BiometricPromptLayout.iconView] and [BiometricPromptLayout.biometric_icon_overlay] to
     * [PromptIconViewModel].
     */
    @JvmStatic
    fun bind(
        iconView: LottieAnimationView,
        iconOverlayView: LottieAnimationView,
        iconViewLayoutParamSizeOverride: Pair<Int, Int>?,
        promptViewModel: PromptViewModel
    ) {
        val viewModel = promptViewModel.iconViewModel
        iconView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.onConfigurationChanged(iconView.context.resources.configuration)
                if (iconViewLayoutParamSizeOverride != null) {
                    iconView.layoutParams.width = iconViewLayoutParamSizeOverride.first
                    iconView.layoutParams.height = iconViewLayoutParamSizeOverride.second

                    iconOverlayView.layoutParams.width = iconViewLayoutParamSizeOverride.first
                    iconOverlayView.layoutParams.height = iconViewLayoutParamSizeOverride.second
                } else {
                    iconView.layoutParams.width = viewModel.fingerprintIconWidth.first()
                    iconView.layoutParams.height = viewModel.fingerprintIconWidth.first()

                    iconOverlayView.layoutParams.width = viewModel.fingerprintIconWidth.first()
                    iconOverlayView.layoutParams.height = viewModel.fingerprintIconWidth.first()
                }

                var faceIcon: AnimatedVectorDrawable? = null
                val faceIconCallback =
                    object : Animatable2.AnimationCallback() {
                        override fun onAnimationStart(drawable: Drawable) {
                            viewModel.onAnimationStart()
                        }

                        override fun onAnimationEnd(drawable: Drawable) {
                            viewModel.onAnimationEnd()
                        }
                    }

                launch {
                    var width = 0
                    var height = 0
                    combine(promptViewModel.size, viewModel.activeAuthType, ::Pair).collect {
                        (_, activeAuthType) ->
                        // Every time after bp shows, [isIconViewLoaded] is set to false in
                        // [BiometricViewSizeBinder]. Then when biometric prompt view is redrew
                        // (when size or activeAuthType changes), we need to update
                        // [isIconViewLoaded] here to keep it correct.
                        when (activeAuthType) {
                            AuthType.Fingerprint,
                            AuthType.Coex -> {
                                /**
                                 * View is only set visible in BiometricViewSizeBinder once
                                 * PromptSize is determined that accounts for iconView size, to
                                 * prevent prompt resizing being visible to the user.
                                 *
                                 * TODO(b/288175072): May be able to remove this once constraint
                                 *   layout is implemented
                                 */
                                iconView.removeAllLottieOnCompositionLoadedListener()
                                iconView.addLottieOnCompositionLoadedListener {
                                    promptViewModel.setIsIconViewLoaded(true)
                                }
                            }
                            AuthType.Face -> {
                                width = viewModel.faceIconWidth
                                height = viewModel.faceIconHeight
                                /**
                                 * Set to true by default since face icon is a drawable, which
                                 * doesn't have a LottieOnCompositionLoadedListener equivalent.
                                 *
                                 * TODO(b/318569643): To be updated once face assets are updated
                                 *   from drawables
                                 */
                                promptViewModel.setIsIconViewLoaded(true)
                            }
                        }

                        if (width != 0 && height != 0) {
                            iconView.layoutParams.width = width
                            iconView.layoutParams.height = height
                            iconOverlayView.layoutParams.width = width
                            iconOverlayView.layoutParams.height = height
                        }
                    }
                }

                launch {
                    viewModel.iconPosition.collect { position ->
                        if (constraintBp() && position != Rect()) {
                            val iconParams = iconView.layoutParams as ConstraintLayout.LayoutParams

                            if (position.left != -1) {
                                iconParams.endToEnd = ConstraintSet.UNSET
                                iconParams.leftMargin = position.left
                            }
                            if (position.top != -1) {
                                iconParams.bottomToBottom = ConstraintSet.UNSET
                                iconParams.topMargin = position.top
                            }
                            iconView.layoutParams = iconParams
                        }
                    }
                }

                launch {
                    viewModel.iconAsset
                        .sample(
                            combine(
                                viewModel.activeAuthType,
                                viewModel.shouldAnimateIconView,
                                viewModel.shouldRepeatAnimation,
                                viewModel.showingError,
                                ::toQuad
                            ),
                            ::toQuint
                        )
                        .collect {
                            (
                                iconAsset,
                                activeAuthType,
                                shouldAnimateIconView,
                                shouldRepeatAnimation,
                                showingError) ->
                            if (iconAsset != -1) {
                                when (activeAuthType) {
                                    AuthType.Fingerprint,
                                    AuthType.Coex -> {
                                        iconView.setAnimation(iconAsset)
                                        iconView.frame = 0

                                        if (shouldAnimateIconView) {
                                            iconView.playAnimation()
                                        }
                                    }
                                    AuthType.Face -> {
                                        faceIcon?.apply {
                                            unregisterAnimationCallback(faceIconCallback)
                                            stop()
                                        }
                                        faceIcon =
                                            iconView.context.getDrawable(iconAsset)
                                                as AnimatedVectorDrawable
                                        faceIcon?.apply {
                                            iconView.setImageDrawable(this)
                                            if (shouldAnimateIconView) {
                                                forceAnimationOnUI()
                                                if (shouldRepeatAnimation) {
                                                    registerAnimationCallback(faceIconCallback)
                                                }
                                                start()
                                            }
                                        }
                                    }
                                }
                                LottieColorUtils.applyDynamicColors(iconView.context, iconView)
                                viewModel.setPreviousIconWasError(showingError)
                            }
                        }
                }

                launch {
                    viewModel.iconOverlayAsset
                        .sample(
                            combine(
                                viewModel.shouldAnimateIconOverlay,
                                viewModel.showingError,
                                ::Pair
                            ),
                            ::toTriple
                        )
                        .collect { (iconOverlayAsset, shouldAnimateIconOverlay, showingError) ->
                            if (iconOverlayAsset != -1) {
                                iconOverlayView.setAnimation(iconOverlayAsset)
                                iconOverlayView.frame = 0
                                LottieColorUtils.applyDynamicColors(
                                    iconOverlayView.context,
                                    iconOverlayView
                                )

                                if (shouldAnimateIconOverlay) {
                                    iconOverlayView.playAnimation()
                                }
                                viewModel.setPreviousIconOverlayWasError(showingError)
                            }
                        }
                }

                launch {
                    viewModel.shouldFlipIconView.collect { shouldFlipIconView ->
                        if (shouldFlipIconView) {
                            iconView.rotation = 180f
                        }
                    }
                }

                launch {
                    viewModel.contentDescriptionId.collect { id ->
                        if (id != -1) {
                            iconView.contentDescription = iconView.context.getString(id)
                        }
                    }
                }
            }
        }
    }
}

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

import android.content.res.Resources
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieOnCompositionLoadedListener
import com.android.settingslib.widget.LottieColorUtils
import com.android.systemui.Flags.constraintBp
import com.android.systemui.biometrics.ui.viewmodel.PromptIconViewModel
import com.android.systemui.biometrics.ui.viewmodel.PromptIconViewModel.AuthType
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.Quad
import com.android.systemui.util.kotlin.Utils.Companion.toQuint
import com.android.systemui.util.kotlin.sample
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "PromptIconViewBinder"

/** Sub-binder for [BiometricPromptLayout.iconView]. */
object PromptIconViewBinder {
    /** Binds [BiometricPromptLayout.iconView] to [PromptIconViewModel]. */
    @JvmStatic
    fun bind(
        iconView: LottieAnimationView,
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
                }

                if (!constraintBp()) {
                    launch {
                        var lottieOnCompositionLoadedListener: LottieOnCompositionLoadedListener? =
                            null

                        viewModel.iconSize.collect { iconSize ->
                            /**
                             * When we bind the BiometricPrompt View and ViewModel in
                             * [BiometricViewBinder], the view is set invisible and
                             * [isIconViewLoaded] is set to false. We configure the iconView with a
                             * LottieOnCompositionLoadedListener that sets [isIconViewLoaded] to
                             * true, in order to wait for the iconView to load before determining
                             * the prompt size, and prevent any prompt resizing from being visible
                             * to the user.
                             *
                             * TODO(b/288175072): May be able to remove this once constraint layout
                             *   is unflagged
                             */
                            if (lottieOnCompositionLoadedListener != null) {
                                iconView.removeLottieOnCompositionLoadedListener(
                                    lottieOnCompositionLoadedListener!!
                                )
                            }
                            lottieOnCompositionLoadedListener = LottieOnCompositionLoadedListener {
                                promptViewModel.setIsIconViewLoaded(true)
                            }
                            iconView.addLottieOnCompositionLoadedListener(
                                lottieOnCompositionLoadedListener!!
                            )

                            if (iconViewLayoutParamSizeOverride == null) {
                                iconView.layoutParams.width = iconSize.first
                                iconView.layoutParams.height = iconSize.second
                            }
                        }
                    }
                }

                launch {
                    viewModel.iconAsset
                        .sample(
                            combine(
                                viewModel.activeAuthType,
                                viewModel.shouldAnimateIconView,
                                viewModel.shouldLoopIconView,
                                viewModel.showingError,
                                ::Quad
                            ),
                            ::toQuint
                        )
                        .collect {
                            (
                                iconAsset,
                                activeAuthType,
                                shouldAnimateIconView,
                                shouldLoopIconView,
                                showingError) ->
                            if (iconAsset != -1) {
                                iconView.updateAsset(
                                    "iconAsset",
                                    iconAsset,
                                    shouldAnimateIconView,
                                    shouldLoopIconView,
                                    activeAuthType
                                )
                                viewModel.setPreviousIconWasError(showingError)
                            }
                        }
                }

                launch {
                    viewModel.iconViewRotation.collect { rotation -> iconView.rotation = rotation }
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

fun LottieAnimationView.updateAsset(
    type: String,
    asset: Int,
    shouldAnimateIconView: Boolean,
    shouldLoopIconView: Boolean,
    activeAuthType: AuthType
) {
    setFailureListener(type, asset, activeAuthType)
    pauseAnimation()
    setAnimation(asset)
    if (animatingFromSfpsAuthenticating(asset)) {
        // Skipping to error / success / unlock segment of animation
        setMinFrame(151)
    } else {
        frame = 0
    }
    if (shouldAnimateIconView) {
        loop(shouldLoopIconView)
        playAnimation()
    }
    LottieColorUtils.applyDynamicColors(context, this)
}

private fun animatingFromSfpsAuthenticating(asset: Int): Boolean =
    asset in sfpsFpToErrorAssets || asset in sfpsFpToUnlockAssets || asset in sfpsFpToSuccessAssets

private val sfpsFpToErrorAssets: List<Int> =
    listOf(
        R.raw.biometricprompt_sfps_fingerprint_to_error,
        R.raw.biometricprompt_sfps_fingerprint_to_error_90,
        R.raw.biometricprompt_sfps_fingerprint_to_error_180,
        R.raw.biometricprompt_sfps_fingerprint_to_error_270,
        R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error,
        R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error_90,
        R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error_180,
        R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error_270,
    )

private val sfpsFpToUnlockAssets: List<Int> =
    listOf(
        R.raw.biometricprompt_sfps_fingerprint_to_unlock,
        R.raw.biometricprompt_sfps_fingerprint_to_unlock_90,
        R.raw.biometricprompt_sfps_fingerprint_to_unlock_180,
        R.raw.biometricprompt_sfps_fingerprint_to_unlock_270,
        R.raw.biometricprompt_sfps_rear_display_fingerprint_to_unlock,
        R.raw.biometricprompt_sfps_rear_display_fingerprint_to_unlock_90,
        R.raw.biometricprompt_sfps_rear_display_fingerprint_to_unlock_180,
        R.raw.biometricprompt_sfps_rear_display_fingerprint_to_unlock_270,
    )

private val sfpsFpToSuccessAssets: List<Int> =
    listOf(
        R.raw.biometricprompt_sfps_fingerprint_to_success,
        R.raw.biometricprompt_sfps_fingerprint_to_success_90,
        R.raw.biometricprompt_sfps_fingerprint_to_success_180,
        R.raw.biometricprompt_sfps_fingerprint_to_success_270,
        R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success,
        R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success_90,
        R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success_180,
        R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success_270,
    )

private fun LottieAnimationView.setFailureListener(type: String, asset: Int, authType: AuthType) {
    val assetName =
        try {
            context.resources.getResourceEntryName(asset)
        } catch (e: Resources.NotFoundException) {
            "Asset $asset not found"
        }

    setFailureListener { result: Throwable? ->
        Log.d(
            TAG,
            "Collecting $type | " +
                "activeAuthType = $authType | " +
                "Invalid resource id: $asset, " +
                "name $assetName",
            result
        )
    }
}

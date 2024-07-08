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
import com.android.systemui.util.kotlin.Utils.Companion.toQuad
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "PromptIconViewBinder"

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

                                iconOverlayView.layoutParams.width = iconSize.first
                                iconOverlayView.layoutParams.height = iconSize.second
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
                                viewModel.showingError,
                                ::Triple
                            ),
                            ::toQuad
                        )
                        .collect { (iconAsset, activeAuthType, shouldAnimateIconView, showingError)
                            ->
                            if (iconAsset != -1) {
                                iconView.updateAsset(
                                    "iconAsset",
                                    iconAsset,
                                    shouldAnimateIconView,
                                    activeAuthType
                                )
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
                                iconOverlayView.updateAsset(
                                    "iconOverlayAsset",
                                    iconOverlayAsset,
                                    shouldAnimateIconOverlay,
                                    AuthType.Fingerprint
                                )
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

private val assetIdToString: Map<Int, String> =
    mapOf(
        // UDFPS assets
        R.raw.fingerprint_dialogue_error_to_fingerprint_lottie to
            "fingerprint_dialogue_error_to_fingerprint_lottie",
        R.raw.fingerprint_dialogue_error_to_success_lottie to
            "fingerprint_dialogue_error_to_success_lottie",
        R.raw.fingerprint_dialogue_fingerprint_to_error_lottie to
            "fingerprint_dialogue_fingerprint_to_error_lottie",
        R.raw.fingerprint_dialogue_fingerprint_to_success_lottie to
            "fingerprint_dialogue_fingerprint_to_success_lottie",
        // SFPS assets
        R.raw.biometricprompt_fingerprint_to_error_landscape to
            "biometricprompt_fingerprint_to_error_landscape",
        R.raw.biometricprompt_folded_base_bottomright to "biometricprompt_folded_base_bottomright",
        R.raw.biometricprompt_folded_base_default to "biometricprompt_folded_base_default",
        R.raw.biometricprompt_folded_base_topleft to "biometricprompt_folded_base_topleft",
        R.raw.biometricprompt_landscape_base to "biometricprompt_landscape_base",
        R.raw.biometricprompt_portrait_base_bottomright to
            "biometricprompt_portrait_base_bottomright",
        R.raw.biometricprompt_portrait_base_topleft to "biometricprompt_portrait_base_topleft",
        R.raw.biometricprompt_symbol_error_to_fingerprint_landscape to
            "biometricprompt_symbol_error_to_fingerprint_landscape",
        R.raw.biometricprompt_symbol_error_to_fingerprint_portrait_bottomright to
            "biometricprompt_symbol_error_to_fingerprint_portrait_bottomright",
        R.raw.biometricprompt_symbol_error_to_fingerprint_portrait_topleft to
            "biometricprompt_symbol_error_to_fingerprint_portrait_topleft",
        R.raw.biometricprompt_symbol_error_to_success_landscape to
            "biometricprompt_symbol_error_to_success_landscape",
        R.raw.biometricprompt_symbol_error_to_success_portrait_bottomright to
            "biometricprompt_symbol_error_to_success_portrait_bottomright",
        R.raw.biometricprompt_symbol_error_to_success_portrait_topleft to
            "biometricprompt_symbol_error_to_success_portrait_topleft",
        R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_bottomright to
            "biometricprompt_symbol_fingerprint_to_error_portrait_bottomright",
        R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_topleft to
            "biometricprompt_symbol_fingerprint_to_error_portrait_topleft",
        R.raw.biometricprompt_symbol_fingerprint_to_success_landscape to
            "biometricprompt_symbol_fingerprint_to_success_landscape",
        R.raw.biometricprompt_symbol_fingerprint_to_success_portrait_bottomright to
            "biometricprompt_symbol_fingerprint_to_success_portrait_bottomright",
        R.raw.biometricprompt_symbol_fingerprint_to_success_portrait_topleft to
            "biometricprompt_symbol_fingerprint_to_success_portrait_topleft",
        // Face assets
        R.raw.face_dialog_wink_from_dark to "face_dialog_wink_from_dark",
        R.raw.face_dialog_dark_to_checkmark to "face_dialog_dark_to_checkmark",
        R.raw.face_dialog_dark_to_error to "face_dialog_dark_to_error",
        R.raw.face_dialog_error_to_idle to "face_dialog_error_to_idle",
        R.raw.face_dialog_idle_static to "face_dialog_idle_static",
        R.raw.face_dialog_authenticating to "face_dialog_authenticating",
        // Co-ex assets
        R.raw.fingerprint_dialogue_unlocked_to_checkmark_success_lottie to
            "fingerprint_dialogue_unlocked_to_checkmark_success_lottie",
        R.raw.fingerprint_dialogue_error_to_unlock_lottie to
            "fingerprint_dialogue_error_to_unlock_lottie",
        R.raw.fingerprint_dialogue_fingerprint_to_unlock_lottie to
            "fingerprint_dialogue_fingerprint_to_unlock_lottie",
    )

private fun getAssetNameFromId(id: Int): String {
    return assetIdToString.getOrDefault(id, "Asset $id not found")
}

fun LottieAnimationView.updateAsset(
    type: String,
    asset: Int,
    shouldAnimateIconView: Boolean,
    activeAuthType: AuthType
) {
    setFailureListener(type, asset, activeAuthType)
    setAnimation(asset)
    frame = 0
    if (shouldAnimateIconView) {
        if (asset == R.raw.face_dialog_authenticating) {
            loop(true)
        } else {
            loop(false)
        }
        playAnimation()
    }
    LottieColorUtils.applyDynamicColors(context, this)
}

private fun LottieAnimationView.setFailureListener(type: String, asset: Int, authType: AuthType) {
    setFailureListener { result: Throwable? ->
        Log.d(
            TAG,
            "Collecting $type | " +
                "activeAuthType = $authType | " +
                "Invalid resource id: $asset, " +
                "name ${getAssetNameFromId(asset)}",
            result
        )
    }
}

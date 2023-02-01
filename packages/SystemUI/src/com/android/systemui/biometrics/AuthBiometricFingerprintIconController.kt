/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.annotation.RawRes
import android.content.Context
import android.content.res.Configuration
import android.hardware.fingerprint.FingerprintManager
import android.view.DisplayInfo
import android.view.Surface
import android.view.View
import com.airbnb.lottie.LottieAnimationView
import com.android.settingslib.widget.LottieColorUtils
import com.android.systemui.R
import com.android.systemui.biometrics.AuthBiometricView.BiometricState
import com.android.systemui.biometrics.AuthBiometricView.STATE_AUTHENTICATED
import com.android.systemui.biometrics.AuthBiometricView.STATE_AUTHENTICATING
import com.android.systemui.biometrics.AuthBiometricView.STATE_AUTHENTICATING_ANIMATING_IN
import com.android.systemui.biometrics.AuthBiometricView.STATE_ERROR
import com.android.systemui.biometrics.AuthBiometricView.STATE_HELP
import com.android.systemui.biometrics.AuthBiometricView.STATE_IDLE
import com.android.systemui.biometrics.AuthBiometricView.STATE_PENDING_CONFIRMATION
import com.android.systemui.unfold.compat.ScreenSizeFoldProvider
import com.android.systemui.unfold.updates.FoldProvider

/** Fingerprint only icon animator for BiometricPrompt.  */
open class AuthBiometricFingerprintIconController(
        context: Context,
        iconView: LottieAnimationView,
        protected val iconViewOverlay: LottieAnimationView
) : AuthIconController(context, iconView), FoldProvider.FoldCallback {

    private var isDeviceFolded: Boolean = false
    private val isSideFps: Boolean
    private val screenSizeFoldProvider: ScreenSizeFoldProvider = ScreenSizeFoldProvider(context)
    var iconLayoutParamSize: Pair<Int, Int> = Pair(1, 1)
        set(value) {
            if (field == value) {
                return
            }
            iconViewOverlay.layoutParams.width = value.first
            iconViewOverlay.layoutParams.height = value.second
            iconView.layoutParams.width = value.first
            iconView.layoutParams.height = value.second
            field = value
        }

    init {
        iconLayoutParamSize = Pair(context.resources.getDimensionPixelSize(
                R.dimen.biometric_dialog_fingerprint_icon_width),
                context.resources.getDimensionPixelSize(
                        R.dimen.biometric_dialog_fingerprint_icon_height))
        var sideFps = false
        (context.getSystemService(Context.FINGERPRINT_SERVICE)
                as FingerprintManager?)?.let { fpm ->
            for (prop in fpm.sensorPropertiesInternal) {
                if (prop.isAnySidefpsType) {
                    sideFps = true
                }
            }
        }
        isSideFps = sideFps
        val displayInfo = DisplayInfo()
        context.display?.getDisplayInfo(displayInfo)
        if (isSideFps && displayInfo.rotation == Surface.ROTATION_180) {
            iconView.rotation = 180f
        }
        screenSizeFoldProvider.registerCallback(this, context.mainExecutor)
        screenSizeFoldProvider.onConfigurationChange(context.resources.configuration)
    }

    private fun updateIconSideFps(@BiometricState lastState: Int, @BiometricState newState: Int) {
        val displayInfo = DisplayInfo()
        context.display?.getDisplayInfo(displayInfo)
        val rotation = displayInfo.rotation
        val iconAnimation = getSideFpsAnimationForTransition(rotation)
        val iconViewOverlayAnimation =
                getSideFpsOverlayAnimationForTransition(lastState, newState, rotation) ?: return

        if (!(lastState == STATE_AUTHENTICATING_ANIMATING_IN && newState == STATE_AUTHENTICATING)) {
            iconView.setAnimation(iconAnimation)
            iconViewOverlay.setAnimation(iconViewOverlayAnimation)
        }

        val iconContentDescription = getIconContentDescription(newState)
        if (iconContentDescription != null) {
            iconView.contentDescription = iconContentDescription
            iconViewOverlay.contentDescription = iconContentDescription
        }

        iconView.frame = 0
        iconViewOverlay.frame = 0
        if (shouldAnimateIconViewForTransition(lastState, newState)) {
            iconView.playAnimation()
        }

        if (shouldAnimateIconViewOverlayForTransition(lastState, newState)) {
            iconViewOverlay.playAnimation()
        }

        LottieColorUtils.applyDynamicColors(context, iconView)
        LottieColorUtils.applyDynamicColors(context, iconViewOverlay)
    }

    private fun updateIconNormal(@BiometricState lastState: Int, @BiometricState newState: Int) {
        val icon = getAnimationForTransition(lastState, newState) ?: return

        if (!(lastState == STATE_AUTHENTICATING_ANIMATING_IN && newState == STATE_AUTHENTICATING)) {
            iconView.setAnimation(icon)
        }

        val iconContentDescription = getIconContentDescription(newState)
        if (iconContentDescription != null) {
            iconView.contentDescription = iconContentDescription
        }

        iconView.frame = 0
        if (shouldAnimateIconViewForTransition(lastState, newState)) {
            iconView.playAnimation()
        }
        LottieColorUtils.applyDynamicColors(context, iconView)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        screenSizeFoldProvider.onConfigurationChange(newConfig)
    }

    override fun updateIcon(@BiometricState lastState: Int, @BiometricState newState: Int) {
        if (isSideFps) {
            updateIconSideFps(lastState, newState)
        } else {
            iconViewOverlay.visibility = View.GONE
            updateIconNormal(lastState, newState)
        }
    }

    private fun getIconContentDescription(@BiometricState newState: Int): CharSequence? {
        val id = when (newState) {
            STATE_IDLE,
            STATE_AUTHENTICATING_ANIMATING_IN,
            STATE_AUTHENTICATING,
            STATE_PENDING_CONFIRMATION,
            STATE_AUTHENTICATED -> R.string.accessibility_fingerprint_dialog_fingerprint_icon
            STATE_ERROR,
            STATE_HELP -> R.string.biometric_dialog_try_again
            else -> null
        }
        return if (id != null) context.getString(id) else null
    }

    protected open fun shouldAnimateIconViewForTransition(
            @BiometricState oldState: Int,
            @BiometricState newState: Int
    ) = when (newState) {
        STATE_HELP,
        STATE_ERROR -> true
        STATE_AUTHENTICATING_ANIMATING_IN,
        STATE_AUTHENTICATING ->
            oldState == STATE_ERROR || oldState == STATE_HELP || oldState == STATE_IDLE
        STATE_AUTHENTICATED -> true
        else -> false
    }

    protected open fun shouldAnimateIconViewOverlayForTransition(
            @BiometricState oldState: Int,
            @BiometricState newState: Int
    ) = when (newState) {
        STATE_HELP,
        STATE_ERROR -> true
        STATE_AUTHENTICATING_ANIMATING_IN,
        STATE_AUTHENTICATING -> oldState == STATE_ERROR || oldState == STATE_HELP
        STATE_AUTHENTICATED -> true
        else -> false
    }

    @RawRes
    protected open fun getAnimationForTransition(
            @BiometricState oldState: Int,
            @BiometricState newState: Int
    ): Int? {
        val id = when (newState) {
            STATE_HELP,
            STATE_ERROR -> {
                R.raw.fingerprint_dialogue_fingerprint_to_error_lottie
            }
            STATE_AUTHENTICATING_ANIMATING_IN,
            STATE_AUTHENTICATING -> {
                if (oldState == STATE_ERROR || oldState == STATE_HELP) {
                    R.raw.fingerprint_dialogue_error_to_fingerprint_lottie
                } else {
                    R.raw.fingerprint_dialogue_fingerprint_to_error_lottie
                }
            }
            STATE_AUTHENTICATED -> {
                if (oldState == STATE_ERROR || oldState == STATE_HELP) {
                    R.raw.fingerprint_dialogue_error_to_success_lottie
                } else {
                    R.raw.fingerprint_dialogue_fingerprint_to_success_lottie
                }
            }
            else -> return null
        }
        return if (id != null) return id else null
    }

    @RawRes
    private fun getSideFpsAnimationForTransition(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> if (isDeviceFolded) {
            R.raw.biometricprompt_folded_base_topleft
        } else {
            R.raw.biometricprompt_portrait_base_topleft
        }
        Surface.ROTATION_270 -> if (isDeviceFolded) {
            R.raw.biometricprompt_folded_base_bottomright
        } else {
            R.raw.biometricprompt_portrait_base_bottomright
        }
        else -> if (isDeviceFolded) {
            R.raw.biometricprompt_folded_base_default
        } else {
            R.raw.biometricprompt_landscape_base
        }
    }

    @RawRes
    private fun getSideFpsOverlayAnimationForTransition(
            @BiometricState oldState: Int,
            @BiometricState newState: Int,
            rotation: Int
    ): Int? = when (newState) {
        STATE_HELP,
        STATE_ERROR -> {
            when (rotation) {
                Surface.ROTATION_0 -> R.raw.biometricprompt_fingerprint_to_error_landscape
                Surface.ROTATION_90 ->
                    R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_topleft
                Surface.ROTATION_180 ->
                    R.raw.biometricprompt_fingerprint_to_error_landscape
                Surface.ROTATION_270 ->
                    R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_bottomright
                else -> R.raw.biometricprompt_fingerprint_to_error_landscape
            }
        }
        STATE_AUTHENTICATING_ANIMATING_IN,
        STATE_AUTHENTICATING -> {
            if (oldState == STATE_ERROR || oldState == STATE_HELP) {
                when (rotation) {
                    Surface.ROTATION_0 ->
                        R.raw.biometricprompt_symbol_error_to_fingerprint_landscape
                    Surface.ROTATION_90 ->
                        R.raw.biometricprompt_symbol_error_to_fingerprint_portrait_topleft
                    Surface.ROTATION_180 ->
                        R.raw.biometricprompt_symbol_error_to_fingerprint_landscape
                    Surface.ROTATION_270 ->
                        R.raw.biometricprompt_symbol_error_to_fingerprint_portrait_bottomright
                    else -> R.raw.biometricprompt_symbol_error_to_fingerprint_landscape
                }
            } else {
                when (rotation) {
                    Surface.ROTATION_0 -> R.raw.biometricprompt_fingerprint_to_error_landscape
                    Surface.ROTATION_90 ->
                        R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_topleft
                    Surface.ROTATION_180 ->
                        R.raw.biometricprompt_fingerprint_to_error_landscape
                    Surface.ROTATION_270 ->
                        R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_bottomright
                    else -> R.raw.biometricprompt_fingerprint_to_error_landscape
                }
            }
        }
        STATE_AUTHENTICATED -> {
            if (oldState == STATE_ERROR || oldState == STATE_HELP) {
                when (rotation) {
                    Surface.ROTATION_0 ->
                        R.raw.biometricprompt_symbol_error_to_success_landscape
                    Surface.ROTATION_90 ->
                        R.raw.biometricprompt_symbol_error_to_success_portrait_topleft
                    Surface.ROTATION_180 ->
                        R.raw.biometricprompt_symbol_error_to_success_landscape
                    Surface.ROTATION_270 ->
                        R.raw.biometricprompt_symbol_error_to_success_portrait_bottomright
                    else -> R.raw.biometricprompt_symbol_error_to_success_landscape
                }
            } else {
                when (rotation) {
                    Surface.ROTATION_0 ->
                        R.raw.biometricprompt_symbol_fingerprint_to_success_landscape
                    Surface.ROTATION_90 ->
                        R.raw.biometricprompt_symbol_fingerprint_to_success_portrait_topleft
                    Surface.ROTATION_180 ->
                        R.raw.biometricprompt_symbol_fingerprint_to_success_landscape
                    Surface.ROTATION_270 ->
                        R.raw.biometricprompt_symbol_fingerprint_to_success_portrait_bottomright
                    else -> R.raw.biometricprompt_symbol_fingerprint_to_success_landscape
                }
            }
        }
        else -> null
    }

    override fun onFoldUpdated(isFolded: Boolean) {
        isDeviceFolded = isFolded
    }
}

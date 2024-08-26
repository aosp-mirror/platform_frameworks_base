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
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.biometrics.BiometricAuthenticator
import android.hardware.biometrics.BiometricConstants
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.Flags
import android.hardware.face.FaceManager
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieCompositionFactory
import com.android.systemui.Flags.constraintBp
import com.android.systemui.biometrics.AuthPanelController
import com.android.systemui.biometrics.Utils.ellipsize
import com.android.systemui.biometrics.shared.model.BiometricModalities
import com.android.systemui.biometrics.shared.model.BiometricModality
import com.android.systemui.biometrics.shared.model.PromptKind
import com.android.systemui.biometrics.shared.model.asBiometricModality
import com.android.systemui.biometrics.ui.BiometricPromptLayout
import com.android.systemui.biometrics.ui.viewmodel.FingerprintStartMode
import com.android.systemui.biometrics.ui.viewmodel.PromptMessage
import com.android.systemui.biometrics.ui.viewmodel.PromptSize
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "BiometricViewBinder"

/** Top-most view binder for BiometricPrompt views. */
object BiometricViewBinder {
    const val MAX_LOGO_DESCRIPTION_CHARACTER_NUMBER = 30

    /** Binds a [BiometricPromptLayout] to a [PromptViewModel]. */
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic
    fun bind(
        view: View,
        viewModel: PromptViewModel,
        panelViewController: AuthPanelController?,
        jankListener: BiometricJankListener,
        backgroundView: View,
        legacyCallback: Spaghetti.Callback,
        applicationScope: CoroutineScope,
        vibratorHelper: VibratorHelper,
    ): Spaghetti {
        /**
         * View is only set visible in BiometricViewSizeBinder once PromptSize is determined that
         * accounts for iconView size, to prevent prompt resizing being visible to the user.
         *
         * TODO(b/288175072): May be able to remove this once constraint layout is implemented
         */
        if (!constraintBp()) {
            view.visibility = View.INVISIBLE
        }
        val accessibilityManager = view.context.getSystemService(AccessibilityManager::class.java)!!

        val textColorError =
            view.resources.getColor(R.color.biometric_dialog_error, view.context.theme)
        val textColorHint =
            view.resources.getColor(R.color.biometric_dialog_gray, view.context.theme)

        val logoView = view.requireViewById<ImageView>(R.id.logo)
        val logoDescriptionView = view.requireViewById<TextView>(R.id.logo_description)
        val titleView = view.requireViewById<TextView>(R.id.title)
        val subtitleView = view.requireViewById<TextView>(R.id.subtitle)
        val descriptionView = view.requireViewById<TextView>(R.id.description)
        val customizedViewContainer =
            view.requireViewById<LinearLayout>(R.id.customized_view_container)
        val udfpsGuidanceView =
            if (constraintBp()) {
                view.requireViewById<View>(R.id.panel)
            } else {
                backgroundView
            }

        // set selected to enable marquee unless a screen reader is enabled
        titleView.isSelected =
            !accessibilityManager.isEnabled || !accessibilityManager.isTouchExplorationEnabled
        subtitleView.isSelected =
            !accessibilityManager.isEnabled || !accessibilityManager.isTouchExplorationEnabled
        descriptionView.movementMethod = ScrollingMovementMethod()

        val iconOverlayView = view.requireViewById<LottieAnimationView>(R.id.biometric_icon_overlay)
        val iconView = view.requireViewById<LottieAnimationView>(R.id.biometric_icon)

        val iconSizeOverride =
            if (constraintBp()) {
                null
            } else {
                (view as BiometricPromptLayout).updatedFingerprintAffordanceSize
            }

        val indicatorMessageView = view.requireViewById<TextView>(R.id.indicator)

        // Negative-side (left) buttons
        val negativeButton = view.requireViewById<Button>(R.id.button_negative)
        val cancelButton = view.requireViewById<Button>(R.id.button_cancel)
        val credentialFallbackButton = view.requireViewById<Button>(R.id.button_use_credential)

        // Positive-side (right) buttons
        val confirmationButton = view.requireViewById<Button>(R.id.button_confirm)
        val retryButton = view.requireViewById<Button>(R.id.button_try_again)

        // TODO(b/330788871): temporary workaround for the unsafe callbacks & legacy controllers
        val adapter =
            Spaghetti(
                view = view,
                viewModel = viewModel,
                applicationContext = view.context.applicationContext,
                applicationScope = applicationScope,
            )

        // bind to prompt
        var boundSize = false

        view.repeatWhenAttached {
            // these do not change and need to be set before any size transitions
            val modalities = viewModel.modalities.first()

            if (modalities.hasFingerprint) {
                /**
                 * Load the given [rawResources] immediately so they are cached for use in the
                 * [context].
                 */
                val rawResources = viewModel.iconViewModel.getRawAssets(modalities.hasSfps)
                for (res in rawResources) {
                    LottieCompositionFactory.fromRawRes(view.context, res)
                }
            }

            logoView.setImageDrawable(viewModel.logo.first())
            // The ellipsize effect on xml happens only when the TextView does not have any free
            // space on the screen to show the text. So we need to manually truncate.
            logoDescriptionView.text =
                viewModel.logoDescription.first().ellipsize(MAX_LOGO_DESCRIPTION_CHARACTER_NUMBER)
            titleView.text = viewModel.title.first()
            subtitleView.text = viewModel.subtitle.first()
            descriptionView.text = viewModel.description.first()

            if (Flags.customBiometricPrompt() && constraintBp()) {
                BiometricCustomizedViewBinder.bind(
                    customizedViewContainer,
                    viewModel.contentView.first(),
                    legacyCallback
                )
            }

            // set button listeners
            negativeButton.setOnClickListener { legacyCallback.onButtonNegative() }
            cancelButton.setOnClickListener { legacyCallback.onUserCanceled() }
            credentialFallbackButton.setOnClickListener {
                viewModel.onSwitchToCredential()
                legacyCallback.onUseDeviceCredential()
            }
            confirmationButton.setOnClickListener { viewModel.confirmAuthenticated() }
            retryButton.setOnClickListener {
                viewModel.showAuthenticating(isRetry = true)
                legacyCallback.onButtonTryAgain()
            }

            adapter.attach(this, modalities, legacyCallback)

            if (!boundSize) {
                boundSize = true
                BiometricViewSizeBinder.bind(
                    view = view,
                    viewModel = viewModel,
                    viewsToHideWhenSmall =
                        listOf(
                            logoView,
                            logoDescriptionView,
                            titleView,
                            subtitleView,
                            descriptionView,
                            customizedViewContainer,
                        ),
                    viewsToFadeInOnSizeChange =
                        listOf(
                            logoView,
                            logoDescriptionView,
                            titleView,
                            subtitleView,
                            descriptionView,
                            customizedViewContainer,
                            indicatorMessageView,
                            negativeButton,
                            cancelButton,
                            retryButton,
                            confirmationButton,
                            credentialFallbackButton,
                        ),
                    panelViewController = panelViewController,
                    jankListener = jankListener,
                )
            }

            lifecycleScope.launch {
                viewModel.hideSensorIcon.collect { showWithoutIcon ->
                    if (!showWithoutIcon) {
                        PromptIconViewBinder.bind(
                            iconView,
                            iconOverlayView,
                            iconSizeOverride,
                            viewModel,
                        )
                    }
                }
            }

            // TODO(b/251476085): migrate legacy icon controllers and remove
            // The fingerprint sensor is started by the legacy
            // AuthContainerView#onDialogAnimatedIn in all cases but the implicit coex flow
            // (delayed mode). In that case, start it on the first transition to delayed
            // which will be triggered by any auth failure.
            lifecycleScope.launch {
                val oldMode = viewModel.fingerprintStartMode.first()
                viewModel.fingerprintStartMode.collect { newMode ->
                    // trigger sensor to start
                    if (
                        oldMode == FingerprintStartMode.Pending &&
                            newMode == FingerprintStartMode.Delayed
                    ) {
                        legacyCallback.onStartDelayedFingerprintSensor()
                    }
                }
            }

            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // handle background clicks
                launch {
                    combine(viewModel.isAuthenticated, viewModel.size) { (authenticated, _), size ->
                            when {
                                authenticated -> false
                                size == PromptSize.SMALL -> false
                                size == PromptSize.LARGE -> false
                                else -> true
                            }
                        }
                        .collect { dismissOnClick ->
                            backgroundView.setOnClickListener {
                                if (dismissOnClick) {
                                    legacyCallback.onUserCanceled()
                                } else {
                                    Log.w(TAG, "Ignoring background click")
                                }
                            }
                        }
                }

                // set messages
                launch {
                    viewModel.isIndicatorMessageVisible.collect { show ->
                        indicatorMessageView.visibility = show.asVisibleOrHidden()
                    }
                }

                // set padding
                launch {
                    viewModel.promptPadding.collect { promptPadding ->
                        if (!constraintBp()) {
                            view.setPadding(
                                promptPadding.left,
                                promptPadding.top,
                                promptPadding.right,
                                promptPadding.bottom
                            )
                        }
                    }
                }

                // configure & hide/disable buttons
                launch {
                    viewModel.credentialKind
                        .map { kind ->
                            when (kind) {
                                PromptKind.Pin ->
                                    view.resources.getString(R.string.biometric_dialog_use_pin)
                                PromptKind.Password ->
                                    view.resources.getString(R.string.biometric_dialog_use_password)
                                PromptKind.Pattern ->
                                    view.resources.getString(R.string.biometric_dialog_use_pattern)
                                else -> ""
                            }
                        }
                        .collect { credentialFallbackButton.text = it }
                }
                launch { viewModel.negativeButtonText.collect { negativeButton.text = it } }
                launch {
                    viewModel.isConfirmButtonVisible.collect { show ->
                        confirmationButton.visibility = show.asVisibleOrGone()
                    }
                }
                launch {
                    viewModel.isCancelButtonVisible.collect { show ->
                        cancelButton.visibility = show.asVisibleOrGone()
                    }
                }
                launch {
                    viewModel.isNegativeButtonVisible.collect { show ->
                        negativeButton.visibility = show.asVisibleOrGone()
                    }
                }
                launch {
                    viewModel.isTryAgainButtonVisible.collect { show ->
                        retryButton.visibility = show.asVisibleOrGone()
                    }
                }
                launch {
                    viewModel.isCredentialButtonVisible.collect { show ->
                        credentialFallbackButton.visibility = show.asVisibleOrGone()
                    }
                }

                // reuse the icon as a confirm button
                launch {
                    viewModel.isIconConfirmButton
                        .map { isPending ->
                            when {
                                isPending && modalities.hasFaceAndFingerprint ->
                                    View.OnTouchListener { _: View, event: MotionEvent ->
                                        viewModel.onOverlayTouch(event)
                                    }
                                else -> null
                            }
                        }
                        .collect { onTouch ->
                            iconOverlayView.setOnTouchListener(onTouch)
                            iconView.setOnTouchListener(onTouch)
                        }
                }

                // dismiss prompt when authenticated and confirmed
                launch {
                    viewModel.isAuthenticated.collect { authState ->
                        // Disable background view for cancelling authentication once authenticated,
                        // and remove from talkback
                        if (authState.isAuthenticated) {
                            // Prevents Talkback from speaking subtitle after already authenticated
                            subtitleView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                            backgroundView.setOnClickListener(null)
                            backgroundView.importantForAccessibility =
                                IMPORTANT_FOR_ACCESSIBILITY_NO

                            // Allow icon to be used as confirmation button with a11y enabled
                            if (accessibilityManager.isTouchExplorationEnabled) {
                                iconOverlayView.setOnClickListener {
                                    viewModel.confirmAuthenticated()
                                }
                                iconView.setOnClickListener { viewModel.confirmAuthenticated() }
                            }
                        }
                        if (authState.isAuthenticatedAndConfirmed) {
                            view.announceForAccessibility(
                                view.resources.getString(R.string.biometric_dialog_authenticated)
                            )

                            launch {
                                delay(authState.delay)
                                if (authState.isAuthenticatedAndExplicitlyConfirmed) {
                                    legacyCallback.onAuthenticatedAndConfirmed()
                                } else {
                                    legacyCallback.onAuthenticated()
                                }
                            }
                        }
                    }
                }

                // show error & help messages
                launch {
                    viewModel.message.collect { promptMessage ->
                        val isError = promptMessage is PromptMessage.Error
                        indicatorMessageView.text = promptMessage.message
                        indicatorMessageView.setTextColor(
                            if (isError) textColorError else textColorHint
                        )

                        // select to enable marquee unless a screen reader is enabled
                        // TODO(wenhuiy): this may have recently changed per UX - verify and remove
                        indicatorMessageView.isSelected =
                            !accessibilityManager.isEnabled ||
                                !accessibilityManager.isTouchExplorationEnabled
                    }
                }

                // Talkback directional guidance
                udfpsGuidanceView.setOnHoverListener { _, event ->
                    launch {
                        viewModel.onAnnounceAccessibilityHint(
                            event,
                            accessibilityManager.isTouchExplorationEnabled
                        )
                    }
                    false
                }
                launch {
                    viewModel.accessibilityHint.collect { message ->
                        if (message.isNotBlank()) view.announceForAccessibility(message)
                    }
                }

                // Play haptics
                launch {
                    viewModel.hapticsToPlay.collect { haptics ->
                        if (haptics.hapticFeedbackConstant != HapticFeedbackConstants.NO_HAPTICS) {
                            if (haptics.flag != null) {
                                vibratorHelper.performHapticFeedback(
                                    view,
                                    haptics.hapticFeedbackConstant,
                                    haptics.flag,
                                )
                            } else {
                                vibratorHelper.performHapticFeedback(
                                    view,
                                    haptics.hapticFeedbackConstant,
                                )
                            }
                            viewModel.clearHaptics()
                        }
                    }
                }

                // Retry and confirmation when finger on sensor
                launch {
                    combine(viewModel.canTryAgainNow, viewModel.hasFingerOnSensor, ::Pair)
                        .collect { (canRetry, fingerAcquired) ->
                            if (canRetry && fingerAcquired) {
                                legacyCallback.onButtonTryAgain()
                            }
                        }
                }
            }
        }

        return adapter
    }
}

/**
 * Adapter for legacy events. Remove once legacy controller can be replaced by flagged code.
 *
 * These events can be dispatched when the view is being recreated so they need to be delivered to
 * the view model (which will be retained) via the application scope.
 *
 * Do not reference the [view] for anything other than [asView].
 */
@Deprecated("TODO(b/330788871): remove after replacing AuthContainerView")
class Spaghetti(
    private val view: View,
    private val viewModel: PromptViewModel,
    private val applicationContext: Context,
    private val applicationScope: CoroutineScope,
) {

    @Deprecated("TODO(b/330788871): remove after replacing AuthContainerView")
    interface Callback {
        fun onAuthenticated()

        fun onUserCanceled()

        fun onButtonNegative()

        fun onButtonTryAgain()

        fun onContentViewMoreOptionsButtonPressed()

        fun onError()

        fun onUseDeviceCredential()

        fun onStartDelayedFingerprintSensor()

        fun onAuthenticatedAndConfirmed()
    }

    @Deprecated("TODO(b/330788871): remove after replacing AuthContainerView")
    enum class BiometricState {
        /** Authentication hardware idle. */
        STATE_IDLE,
        /** UI animating in, authentication hardware active. */
        STATE_AUTHENTICATING_ANIMATING_IN,
        /** UI animated in, authentication hardware active. */
        STATE_AUTHENTICATING,
        /** UI animated in, authentication hardware active. */
        STATE_HELP,
        /** Hard error, e.g. ERROR_TIMEOUT. Authentication hardware idle. */
        STATE_ERROR,
        /** Authenticated, waiting for user confirmation. Authentication hardware idle. */
        STATE_PENDING_CONFIRMATION,
        /** Authenticated, dialog animating away soon. */
        STATE_AUTHENTICATED,
    }

    private var lifecycleScope: CoroutineScope? = null
    private var modalities: BiometricModalities = BiometricModalities()
    private var legacyCallback: Callback? = null

    // hacky way to suppress lockout errors
    private val lockoutErrorStrings =
        listOf(
                BiometricConstants.BIOMETRIC_ERROR_LOCKOUT,
                BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
            )
            .map { FaceManager.getErrorString(applicationContext, it, 0 /* vendorCode */) }

    fun attach(
        lifecycleOwner: LifecycleOwner,
        activeModalities: BiometricModalities,
        callback: Callback,
    ) {
        modalities = activeModalities
        legacyCallback = callback

        lifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    lifecycleScope = owner.lifecycleScope
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    lifecycleScope = null
                }
            }
        )
    }

    fun onDialogAnimatedIn(fingerprintWasStarted: Boolean) {
        if (fingerprintWasStarted) {
            viewModel.ensureFingerprintHasStarted(isDelayed = false)
            viewModel.showAuthenticating(modalities.asDefaultHelpMessage(applicationContext))
        } else {
            viewModel.showAuthenticating()
        }
    }

    fun onAuthenticationSucceeded(@BiometricAuthenticator.Modality modality: Int) {
        applicationScope.launch {
            val authenticatedModality = modality.asBiometricModality()
            val msgId = getHelpForSuccessfulAuthentication(authenticatedModality)
            viewModel.showAuthenticated(
                modality = authenticatedModality,
                dismissAfterDelay = 500,
                helpMessage = if (msgId != null) applicationContext.getString(msgId) else ""
            )
        }
    }

    private fun getHelpForSuccessfulAuthentication(
        authenticatedModality: BiometricModality,
    ): Int? {
        // for coex, show a message when face succeeds after fingerprint has also started
        if (authenticatedModality != BiometricModality.Face) {
            return null
        }

        if (modalities.hasUdfps) {
            return R.string.biometric_dialog_tap_confirm_with_face_alt_1
        }
        if (modalities.hasSfps) {
            return R.string.biometric_dialog_tap_confirm_with_face_sfps
        }
        return null
    }

    fun onAuthenticationFailed(
        @BiometricAuthenticator.Modality modality: Int,
        failureReason: String,
    ) {
        val failedModality = modality.asBiometricModality()
        viewModel.ensureFingerprintHasStarted(isDelayed = true)

        applicationScope.launch {
            viewModel.showTemporaryError(
                failureReason,
                messageAfterError = modalities.asDefaultHelpMessage(applicationContext),
                authenticateAfterError = modalities.hasFingerprint,
                suppressIf = { currentMessage, history ->
                    modalities.hasFaceAndFingerprint &&
                        failedModality == BiometricModality.Face &&
                        (currentMessage.isError || history.faceFailed)
                },
                failedModality = failedModality,
            )
        }
    }

    fun onError(modality: Int, error: String) {
        val errorModality = modality.asBiometricModality()
        if (ignoreUnsuccessfulEventsFrom(errorModality, error)) {
            return
        }

        applicationScope.launch {
            viewModel.showTemporaryError(
                error,
                messageAfterError = modalities.asDefaultHelpMessage(applicationContext),
                authenticateAfterError = modalities.hasFingerprint,
            )
            delay(BiometricPrompt.HIDE_DIALOG_DELAY.toLong())
            legacyCallback?.onError()
        }
    }

    fun onHelp(modality: Int, help: String) {
        if (ignoreUnsuccessfulEventsFrom(modality.asBiometricModality(), "")) {
            return
        }

        applicationScope.launch {
            // help messages from the HAL should be displayed as temporary (i.e. soft) errors
            viewModel.showTemporaryError(
                help,
                messageAfterError = modalities.asDefaultHelpMessage(applicationContext),
                authenticateAfterError = modalities.hasFingerprint,
                hapticFeedback = false,
            )
        }
    }

    private fun ignoreUnsuccessfulEventsFrom(modality: BiometricModality, message: String) =
        when {
            modalities.hasFaceAndFingerprint ->
                (modality == BiometricModality.Face) &&
                    !(modalities.isFaceStrong && lockoutErrorStrings.contains(message))
            else -> false
        }

    fun startTransitionToCredentialUI(isError: Boolean) {
        applicationScope.launch {
            viewModel.onSwitchToCredential()
            legacyCallback?.onUseDeviceCredential()
        }
    }

    fun cancelAnimation() {
        view.animate()?.cancel()
    }

    fun isCoex() = modalities.hasFaceAndFingerprint

    fun isFaceOnly() = modalities.hasFaceOnly

    fun asView() = view
}

private fun BiometricModalities.asDefaultHelpMessage(context: Context): String =
    when {
        hasFingerprint -> context.getString(R.string.fingerprint_dialog_touch_sensor)
        else -> ""
    }

private fun Boolean.asVisibleOrGone(): Int = if (this) View.VISIBLE else View.GONE

private fun Boolean.asVisibleOrHidden(): Int = if (this) View.VISIBLE else View.INVISIBLE

// TODO(b/251476085): proper type?
typealias BiometricJankListener = Animator.AnimatorListener

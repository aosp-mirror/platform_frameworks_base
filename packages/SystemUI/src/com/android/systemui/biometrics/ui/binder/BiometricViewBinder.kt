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
import android.content.Context
import android.hardware.biometrics.BiometricAuthenticator
import android.hardware.biometrics.BiometricConstants
import android.hardware.biometrics.BiometricPrompt
import android.hardware.face.FaceManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.LottieAnimationView
import com.android.systemui.R
import com.android.systemui.biometrics.AuthBiometricFaceIconController
import com.android.systemui.biometrics.AuthBiometricFingerprintAndFaceIconController
import com.android.systemui.biometrics.AuthBiometricFingerprintIconController
import com.android.systemui.biometrics.AuthBiometricView
import com.android.systemui.biometrics.AuthBiometricView.Callback
import com.android.systemui.biometrics.AuthBiometricViewAdapter
import com.android.systemui.biometrics.AuthIconController
import com.android.systemui.biometrics.AuthPanelController
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.domain.model.BiometricModalities
import com.android.systemui.biometrics.domain.model.BiometricModality
import com.android.systemui.biometrics.domain.model.asBiometricModality
import com.android.systemui.biometrics.shared.model.PromptKind
import com.android.systemui.biometrics.ui.BiometricPromptLayout
import com.android.systemui.biometrics.ui.viewmodel.FingerprintStartMode
import com.android.systemui.biometrics.ui.viewmodel.PromptMessage
import com.android.systemui.biometrics.ui.viewmodel.PromptSize
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "BiometricViewBinder"

/** Top-most view binder for BiometricPrompt views. */
object BiometricViewBinder {

    /** Binds a [BiometricPromptLayout] to a [PromptViewModel]. */
    @JvmStatic
    fun bind(
        view: BiometricPromptLayout,
        viewModel: PromptViewModel,
        panelViewController: AuthPanelController,
        jankListener: BiometricJankListener,
        backgroundView: View,
        legacyCallback: Callback,
        applicationScope: CoroutineScope,
    ): AuthBiometricViewAdapter {
        val accessibilityManager = view.context.getSystemService(AccessibilityManager::class.java)!!
        fun notifyAccessibilityChanged() {
            Utils.notifyAccessibilityContentChanged(accessibilityManager, view)
        }

        val textColorError =
            view.resources.getColor(R.color.biometric_dialog_error, view.context.theme)
        val textColorHint =
            view.resources.getColor(R.color.biometric_dialog_gray, view.context.theme)

        val titleView = view.requireViewById<TextView>(R.id.title)
        val subtitleView = view.requireViewById<TextView>(R.id.subtitle)
        val descriptionView = view.requireViewById<TextView>(R.id.description)

        // set selected for marquee
        titleView.isSelected = true
        subtitleView.isSelected = true
        descriptionView.movementMethod = ScrollingMovementMethod()

        val iconViewOverlay = view.requireViewById<LottieAnimationView>(R.id.biometric_icon_overlay)
        val iconView = view.requireViewById<LottieAnimationView>(R.id.biometric_icon)
        val indicatorMessageView = view.requireViewById<TextView>(R.id.indicator)

        // Negative-side (left) buttons
        val negativeButton = view.requireViewById<Button>(R.id.button_negative)
        val cancelButton = view.requireViewById<Button>(R.id.button_cancel)
        val credentialFallbackButton = view.requireViewById<Button>(R.id.button_use_credential)

        // Positive-side (right) buttons
        val confirmationButton = view.requireViewById<Button>(R.id.button_confirm)
        val retryButton = view.requireViewById<Button>(R.id.button_try_again)

        // TODO(b/251476085): temporary workaround for the unsafe callbacks & legacy controllers
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
            titleView.text = viewModel.title.first()
            descriptionView.text = viewModel.description.first()
            subtitleView.text = viewModel.subtitle.first()

            // set button listeners
            negativeButton.setOnClickListener {
                legacyCallback.onAction(Callback.ACTION_BUTTON_NEGATIVE)
            }
            cancelButton.setOnClickListener {
                legacyCallback.onAction(Callback.ACTION_USER_CANCELED)
            }
            credentialFallbackButton.setOnClickListener {
                viewModel.onSwitchToCredential()
                legacyCallback.onAction(Callback.ACTION_USE_DEVICE_CREDENTIAL)
            }
            confirmationButton.setOnClickListener { viewModel.confirmAuthenticated() }
            retryButton.setOnClickListener {
                viewModel.showAuthenticating(isRetry = true)
                legacyCallback.onAction(Callback.ACTION_BUTTON_TRY_AGAIN)
            }

            // TODO(b/251476085): migrate legacy icon controllers and remove
            var legacyState: Int = viewModel.legacyState.value
            val iconController =
                modalities.asIconController(
                    view.context,
                    iconView,
                    iconViewOverlay,
                )
            adapter.attach(this, iconController, modalities, legacyCallback)
            if (iconController is AuthBiometricFingerprintIconController) {
                view.updateFingerprintAffordanceSize(iconController)
            }
            if (iconController is HackyCoexIconController) {
                iconController.faceMode = !viewModel.isConfirmationRequested.first()
            }

            // the icon controller must be created before this happens for the legacy
            // sizing code in BiometricPromptLayout to work correctly. Simplify this
            // when those are also migrated. (otherwise the icon size may not be set to
            // a pixel value before the view is measured and WRAP_CONTENT will be incorrectly
            // used as part of the measure spec)
            if (!boundSize) {
                boundSize = true
                BiometricViewSizeBinder.bind(
                    view = view,
                    viewModel = viewModel,
                    viewsToHideWhenSmall =
                        listOf(
                            titleView,
                            subtitleView,
                            descriptionView,
                        ),
                    viewsToFadeInOnSizeChange =
                        listOf(
                            titleView,
                            subtitleView,
                            descriptionView,
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
                        legacyCallback.onAction(Callback.ACTION_START_DELAYED_FINGERPRINT_SENSOR)
                    }

                    if (newMode.isStarted) {
                        // do wonky switch from implicit to explicit flow
                        (iconController as? HackyCoexIconController)?.faceMode = false
                        viewModel.showAuthenticating(
                            modalities.asDefaultHelpMessage(view.context),
                        )
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
                                    legacyCallback.onAction(Callback.ACTION_USER_CANCELED)
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
                    viewModel.isConfirmButtonVisible
                        .map { isPending ->
                            when {
                                isPending && iconController.actsAsConfirmButton ->
                                    View.OnClickListener { viewModel.confirmAuthenticated() }
                                else -> null
                            }
                        }
                        .collect { onClick ->
                            iconViewOverlay.setOnClickListener(onClick)
                            iconView.setOnClickListener(onClick)
                        }
                }

                // TODO(b/251476085): remove w/ legacy icon controllers
                // set icon affordance using legacy states
                // like the old code, this causes animations to repeat on config changes :(
                // but keep behavior for now as no one has complained...
                launch {
                    viewModel.legacyState.collect { newState ->
                        iconController.updateState(legacyState, newState)
                        legacyState = newState
                    }
                }

                // not sure why this is here, but the legacy code did it probably needed?
                launch {
                    viewModel.isAuthenticating.collect { isAuthenticating ->
                        if (isAuthenticating) {
                            notifyAccessibilityChanged()
                        }
                    }
                }

                // dismiss prompt when authenticated and confirmed
                launch {
                    viewModel.isAuthenticated.collect { authState ->
                        if (authState.isAuthenticatedAndConfirmed) {
                            view.announceForAccessibility(
                                view.resources.getString(R.string.biometric_dialog_authenticated)
                            )
                            notifyAccessibilityChanged()

                            launch {
                                delay(authState.delay)
                                legacyCallback.onAction(Callback.ACTION_AUTHENTICATED)
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

                        notifyAccessibilityChanged()
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
 *
 * TODO(b/251476085): remove after replacing AuthContainerView
 */
private class Spaghetti(
    private val view: View,
    private val viewModel: PromptViewModel,
    private val applicationContext: Context,
    private val applicationScope: CoroutineScope,
) : AuthBiometricViewAdapter {

    private var lifecycleScope: CoroutineScope? = null
    private var modalities: BiometricModalities = BiometricModalities()
    private var faceFailedAtLeastOnce = false
    private var legacyCallback: Callback? = null

    override var legacyIconController: AuthIconController? = null
        private set

    // hacky way to suppress lockout errors
    private val lockoutErrorStrings =
        listOf(
                BiometricConstants.BIOMETRIC_ERROR_LOCKOUT,
                BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
            )
            .map { FaceManager.getErrorString(applicationContext, it, 0 /* vendorCode */) }

    fun attach(
        lifecycleOwner: LifecycleOwner,
        iconController: AuthIconController,
        activeModalities: BiometricModalities,
        callback: Callback,
    ) {
        modalities = activeModalities
        legacyIconController = iconController
        legacyCallback = callback

        lifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    lifecycleScope = owner.lifecycleScope
                    iconController.deactivated = false
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    lifecycleScope = null
                    iconController.deactivated = true
                }
            }
        )
    }

    override fun onDialogAnimatedIn(fingerprintWasStarted: Boolean) {
        if (fingerprintWasStarted) {
            viewModel.ensureFingerprintHasStarted(isDelayed = false)
            viewModel.showAuthenticating(modalities.asDefaultHelpMessage(applicationContext))
        } else {
            viewModel.showAuthenticating()
        }
    }

    override fun onAuthenticationSucceeded(@BiometricAuthenticator.Modality modality: Int) {
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

    private suspend fun getHelpForSuccessfulAuthentication(
        authenticatedModality: BiometricModality,
    ): Int? =
        when {
            // for coex, show a message when face succeeds after fingerprint has also started
            modalities.hasFaceAndFingerprint &&
                (viewModel.fingerprintStartMode.first() != FingerprintStartMode.Pending) &&
                (authenticatedModality == BiometricModality.Face) ->
                R.string.biometric_dialog_tap_confirm_with_face
            else -> null
        }

    override fun onAuthenticationFailed(
        @BiometricAuthenticator.Modality modality: Int,
        failureReason: String,
    ) {
        val failedModality = modality.asBiometricModality()
        viewModel.ensureFingerprintHasStarted(isDelayed = true)

        applicationScope.launch {
            val suppress =
                modalities.hasFaceAndFingerprint &&
                    (failedModality == BiometricModality.Face) &&
                    faceFailedAtLeastOnce
            if (failedModality == BiometricModality.Face) {
                faceFailedAtLeastOnce = true
            }

            viewModel.showTemporaryError(
                failureReason,
                messageAfterError = modalities.asDefaultHelpMessage(applicationContext),
                authenticateAfterError = modalities.hasFingerprint,
                suppressIfErrorShowing = suppress,
                failedModality = failedModality,
            )
        }
    }

    override fun onError(modality: Int, error: String) {
        val errorModality = modality.asBiometricModality()
        if (ignoreUnsuccessfulEventsFrom(errorModality, error)) {
            return
        }

        applicationScope.launch {
            val suppress =
                modalities.hasFaceAndFingerprint && (errorModality == BiometricModality.Face)
            viewModel.showTemporaryError(
                error,
                suppressIfErrorShowing = suppress,
            )
            delay(BiometricPrompt.HIDE_DIALOG_DELAY.toLong())
            legacyCallback?.onAction(Callback.ACTION_ERROR)
        }
    }

    override fun onHelp(modality: Int, help: String) {
        if (ignoreUnsuccessfulEventsFrom(modality.asBiometricModality(), "")) {
            return
        }

        applicationScope.launch {
            viewModel.showTemporaryHelp(
                help,
                messageAfterHelp = modalities.asDefaultHelpMessage(applicationContext),
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

    override fun startTransitionToCredentialUI() {
        applicationScope.launch {
            viewModel.onSwitchToCredential()
            legacyCallback?.onAction(Callback.ACTION_USE_DEVICE_CREDENTIAL)
        }
    }

    override fun requestLayout() {
        // nothing, for legacy view...
    }

    override fun restoreState(bundle: Bundle?) {
        // nothing, for legacy view...
    }

    override fun onSaveState(bundle: Bundle?) {
        // nothing, for legacy view...
    }

    override fun onOrientationChanged() {
        // nothing, for legacy view...
    }

    override fun cancelAnimation() {
        view.animate()?.cancel()
    }

    override fun isCoex() = modalities.hasFaceAndFingerprint

    override fun asView() = view
}

private fun BiometricModalities.asDefaultHelpMessage(context: Context): String =
    when {
        hasFingerprint -> context.getString(R.string.fingerprint_dialog_touch_sensor)
        else -> ""
    }

private fun BiometricModalities.asIconController(
    context: Context,
    iconView: LottieAnimationView,
    iconViewOverlay: LottieAnimationView,
): AuthIconController =
    when {
        hasFaceAndFingerprint -> HackyCoexIconController(context, iconView, iconViewOverlay)
        hasFingerprint -> AuthBiometricFingerprintIconController(context, iconView, iconViewOverlay)
        hasFace -> AuthBiometricFaceIconController(context, iconView)
        else -> throw IllegalStateException("unexpected view type :$this")
    }

private fun Boolean.asVisibleOrGone(): Int = if (this) View.VISIBLE else View.GONE

private fun Boolean.asVisibleOrHidden(): Int = if (this) View.VISIBLE else View.INVISIBLE

// TODO(b/251476085): proper type?
typealias BiometricJankListener = Animator.AnimatorListener

// TODO(b/251476085): delete - temporary until the legacy icon controllers are replaced
private class HackyCoexIconController(
    context: Context,
    iconView: LottieAnimationView,
    iconViewOverlay: LottieAnimationView,
) : AuthBiometricFingerprintAndFaceIconController(context, iconView, iconViewOverlay) {

    private var state: Int? = null
    private val faceController = AuthBiometricFaceIconController(context, iconView)

    var faceMode: Boolean = true
        set(value) {
            if (field != value) {
                field = value

                faceController.deactivated = !value
                iconView.setImageIcon(null)
                iconViewOverlay.setImageIcon(null)
                state?.let { updateIcon(AuthBiometricView.STATE_IDLE, it) }
            }
        }

    override fun updateIcon(lastState: Int, newState: Int) {
        if (deactivated) {
            return
        }

        if (faceMode) {
            faceController.updateIcon(lastState, newState)
        } else {
            super.updateIcon(lastState, newState)
        }

        state = newState
    }
}

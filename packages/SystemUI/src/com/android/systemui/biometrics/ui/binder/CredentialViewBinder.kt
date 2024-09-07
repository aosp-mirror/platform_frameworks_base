package com.android.systemui.biometrics.ui.binder

import android.hardware.biometrics.Flags
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.systemui.Flags.constraintBp
import com.android.systemui.biometrics.AuthPanelController
import com.android.systemui.biometrics.ui.CredentialPasswordView
import com.android.systemui.biometrics.ui.CredentialPatternView
import com.android.systemui.biometrics.ui.CredentialView
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val ANIMATE_CREDENTIAL_INITIAL_DURATION_MS = 150

/**
 * View binder for all credential variants of BiometricPrompt, including [CredentialPatternView] and
 * [CredentialPasswordView].
 *
 * This binder delegates to sub-binders for each variant, such as the [CredentialPasswordViewBinder]
 * and [CredentialPatternViewBinder].
 */
object CredentialViewBinder {

    /** Binds a [CredentialPasswordView] or [CredentialPatternView] to a [CredentialViewModel]. */
    @JvmStatic
    fun bind(
        view: ViewGroup,
        host: CredentialView.Host,
        viewModel: CredentialViewModel,
        panelViewController: AuthPanelController,
        animatePanel: Boolean,
        legacyCallback: Spaghetti.Callback,
        maxErrorDuration: Long = 3_000L,
        requestFocusForInput: Boolean = true,
    ) {
        val titleView: TextView = view.requireViewById(R.id.title)
        val subtitleView: TextView = view.requireViewById(R.id.subtitle)
        val descriptionView: TextView = view.requireViewById(R.id.description)
        val customizedViewContainer: LinearLayout =
            view.requireViewById(R.id.customized_view_container)
        val iconView: ImageView? = view.findViewById(R.id.icon)
        val errorView: TextView = view.requireViewById(R.id.error)
        val cancelButton: Button? = view.findViewById(R.id.cancel_button)
        val emergencyButtonView: Button = view.requireViewById(R.id.emergencyCallButton)

        var errorTimer: Job? = null

        // bind common elements
        view.repeatWhenAttached {
            if (animatePanel) {
                with(panelViewController) {
                    // Credential view is always full screen.
                    setUseFullScreen(true)
                    updateForContentDimensions(
                        containerWidth,
                        containerHeight,
                        0 // animateDurationMs
                    )
                }
            }

            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // show prompt metadata
                launch {
                    viewModel.header.collect { header ->
                        titleView.text = header.title
                        view.announceForAccessibility(header.title)

                        subtitleView.textOrHide = header.subtitle
                        descriptionView.textOrHide = header.description
                        if (Flags.customBiometricPrompt() && constraintBp()) {
                            BiometricCustomizedViewBinder.bind(
                                customizedViewContainer,
                                header.contentView,
                                legacyCallback
                            )
                        }

                        iconView?.setImageDrawable(header.icon)

                        if (header.showEmergencyCallButton) {
                            emergencyButtonView.visibility = View.VISIBLE
                            emergencyButtonView.setOnClickListener {
                                viewModel.doEmergencyCall(view.context)
                            }
                        }

                        // Only animate this if we're transitioning from a biometric view.
                        if (viewModel.animateContents.value) {
                            view.animateCredentialViewIn()
                        }
                    }
                }

                // show transient error messages
                launch {
                    viewModel.errorMessage
                        .onEach { msg ->
                            errorTimer?.cancel()
                            if (msg.isNotBlank()) {
                                errorTimer = launch {
                                    delay(maxErrorDuration)
                                    viewModel.resetErrorMessage()
                                }
                            }
                        }
                        .collect { it ->
                            val hasError = !it.isNullOrBlank()
                            errorView.visibility =
                                if (hasError) {
                                    View.VISIBLE
                                } else if (cancelButton != null) {
                                    View.INVISIBLE
                                } else {
                                    View.GONE
                                }
                            errorView.text = if (hasError) it else ""
                        }
                }

                // show an extra dialog if the remaining attempts becomes low
                launch {
                    viewModel.remainingAttempts
                        .filter { it.remaining != null }
                        .collect { info ->
                            host.onCredentialAttemptsRemaining(info.remaining!!, info.message)
                        }
                }
            }
        }

        cancelButton?.setOnClickListener { host.onCredentialAborted() }

        // bind the auth widget
        when (view) {
            is CredentialPasswordView ->
                CredentialPasswordViewBinder.bind(view, host, viewModel, requestFocusForInput)
            is CredentialPatternView -> CredentialPatternViewBinder.bind(view, host, viewModel)
            else -> throw IllegalStateException("unexpected view type: ${view.javaClass.name}")
        }
    }
}

private fun View.animateCredentialViewIn() {
    translationY = resources.getDimension(R.dimen.biometric_dialog_credential_translation_offset)
    alpha = 0f
    postOnAnimation {
        animate()
            .translationY(0f)
            .setDuration(ANIMATE_CREDENTIAL_INITIAL_DURATION_MS.toLong())
            .alpha(1f)
            .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
            .withLayer()
            .start()
    }
}

private var TextView.textOrHide: String?
    set(value) {
        val gone = value.isNullOrBlank()
        visibility = if (gone) View.GONE else View.VISIBLE
        text = if (gone) "" else value
    }
    get() = text?.toString()

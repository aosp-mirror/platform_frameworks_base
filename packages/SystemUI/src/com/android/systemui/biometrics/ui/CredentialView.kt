package com.android.systemui.biometrics.ui

import com.android.systemui.biometrics.AuthPanelController
import com.android.systemui.biometrics.ui.binder.Spaghetti
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel

/** A credential variant of BiometricPrompt. */
sealed interface CredentialView {
    /**
     * Callbacks for the "host" container view that contains this credential view.
     *
     * TODO(b/251476085): Removed when the host view is converted to use a parent view model.
     */
    interface Host {
        /** When the user's credential has been verified. */
        fun onCredentialMatched(attestation: ByteArray)

        /** When the user abandons credential verification. */
        fun onCredentialAborted()

        /** Warn the user is warned about excessive attempts. */
        fun onCredentialAttemptsRemaining(remaining: Int, messageBody: String)
    }

    // TODO(251476085): remove AuthPanelController
    fun init(
        viewModel: CredentialViewModel,
        host: Host,
        panelViewController: AuthPanelController,
        animatePanel: Boolean,
        legacyCallback: Spaghetti.Callback,
    )
}

package com.android.systemui.biometrics.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import com.android.systemui.biometrics.AuthPanelController
import com.android.systemui.biometrics.ui.binder.CredentialViewBinder
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel

/** Pattern credential view for BiometricPrompt. */
class CredentialPatternView(context: Context, attrs: AttributeSet?) :
    LinearLayout(context, attrs), CredentialView {

    /** Initializes the view. */
    override fun init(
        viewModel: CredentialViewModel,
        host: CredentialView.Host,
        panelViewController: AuthPanelController,
        animatePanel: Boolean,
    ) {
        CredentialViewBinder.bind(this, host, viewModel, panelViewController, animatePanel)
    }
}

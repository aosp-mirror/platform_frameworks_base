package com.android.systemui.biometrics.ui

import android.content.Context
import android.graphics.Insets
import android.util.AttributeSet
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsets.Type
import android.widget.LinearLayout
import com.android.systemui.biometrics.AuthPanelController
import com.android.systemui.biometrics.ui.binder.CredentialViewBinder
import com.android.systemui.biometrics.ui.binder.Spaghetti
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel

/** Pattern credential view for BiometricPrompt. */
class CredentialPatternView(context: Context, attrs: AttributeSet?) :
    LinearLayout(context, attrs), CredentialView, View.OnApplyWindowInsetsListener {

    /** Initializes the view. */
    override fun init(
        viewModel: CredentialViewModel,
        host: CredentialView.Host,
        panelViewController: AuthPanelController,
        animatePanel: Boolean,
        legacyCallback: Spaghetti.Callback,
    ) {
        CredentialViewBinder.bind(
            this,
            host,
            viewModel,
            panelViewController,
            animatePanel,
            legacyCallback
        )
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        setOnApplyWindowInsetsListener(this)
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsets): WindowInsets {
        val statusBarInsets: Insets = insets.getInsets(Type.statusBars())
        val navigationInsets: Insets = insets.getInsets(Type.navigationBars())

        setPadding(0, statusBarInsets.top, 0, navigationInsets.bottom)
        return WindowInsets.CONSUMED
    }
}

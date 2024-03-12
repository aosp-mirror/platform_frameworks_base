package com.android.systemui.biometrics.ui

import android.content.Context
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Insets
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.res.R
import com.android.systemui.biometrics.AuthPanelController
import com.android.systemui.biometrics.ui.binder.CredentialViewBinder
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel

/** PIN or password credential view for BiometricPrompt. */
class CredentialPasswordView(context: Context, attrs: AttributeSet?) :
    LinearLayout(context, attrs), CredentialView, View.OnApplyWindowInsetsListener {

    private var bottomInset: Int = 0

    private val accessibilityManager by lazy {
        context.getSystemService(AccessibilityManager::class.java)
    }

    /** Initializes the view. */
    override fun init(
        viewModel: CredentialViewModel,
        host: CredentialView.Host,
        panelViewController: AuthPanelController,
        animatePanel: Boolean,
    ) {
        CredentialViewBinder.bind(this, host, viewModel, panelViewController, animatePanel)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        setOnApplyWindowInsetsListener(this)
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsets): WindowInsets {
        val statusBarInsets: Insets = insets.getInsets(WindowInsets.Type.statusBars())
        val keyboardInsets: Insets = insets.getInsets(WindowInsets.Type.ime())
        val navigationInsets: Insets = insets.getInsets(WindowInsets.Type.navigationBars())
        val imeBottomInset = keyboardInsets.bottom
        if (bottomInset != imeBottomInset) {
            val titleView: TextView? = findViewById(R.id.title)
            if (titleView != null) {
                if (
                    bottomInset > 0 && resources.configuration.orientation == ORIENTATION_LANDSCAPE
                ) {
                    titleView.isSingleLine = true
                    titleView.ellipsize = TextUtils.TruncateAt.MARQUEE
                    titleView.marqueeRepeatLimit = -1
                    // select to enable marquee unless a screen reader is enabled
                    titleView.isSelected = accessibilityManager?.shouldMarquee() ?: false
                } else {
                    titleView.isSingleLine = false
                    titleView.ellipsize = null
                    // select to enable marquee unless a screen reader is enabled
                    titleView.isSelected = false
                }
            }
        }

        setPadding(
            0,
            statusBarInsets.top,
            0,
            if (keyboardInsets.bottom == 0) navigationInsets.bottom else keyboardInsets.bottom
        )
        return WindowInsets.CONSUMED
    }
}

private fun AccessibilityManager.shouldMarquee(): Boolean = !isEnabled || !isTouchExplorationEnabled

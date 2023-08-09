package com.android.systemui.biometrics.ui

import android.content.Context
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsets.Type.ime
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.ImeAwareEditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isGone
import com.android.systemui.R
import com.android.systemui.biometrics.AuthPanelController
import com.android.systemui.biometrics.ui.binder.CredentialViewBinder
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel

/** PIN or password credential view for BiometricPrompt. */
class CredentialPasswordView(context: Context, attrs: AttributeSet?) :
    LinearLayout(context, attrs), CredentialView, View.OnApplyWindowInsetsListener {

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var iconView: ImageView
    private lateinit var passwordField: ImeAwareEditText
    private lateinit var credentialHeader: View
    private lateinit var credentialInput: View

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

        titleView = requireViewById(R.id.title)
        subtitleView = requireViewById(R.id.subtitle)
        descriptionView = requireViewById(R.id.description)
        iconView = requireViewById(R.id.icon)
        subtitleView = requireViewById(R.id.subtitle)
        passwordField = requireViewById(R.id.lockPassword)
        credentialHeader = requireViewById(R.id.auth_credential_header)
        credentialInput = requireViewById(R.id.auth_credential_input)

        setOnApplyWindowInsetsListener(this)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, left, top, right, bottom)

        val inputLeftBound: Int
        var inputTopBound: Int
        var headerRightBound = right
        var headerTopBounds = top
        var headerBottomBounds = bottom
        val subTitleBottom: Int = if (subtitleView.isGone) titleView.bottom else subtitleView.bottom
        val descBottom = if (descriptionView.isGone) subTitleBottom else descriptionView.bottom
        if (resources.configuration.orientation == ORIENTATION_LANDSCAPE) {
            inputTopBound = (bottom - credentialInput.height) / 2
            inputLeftBound = (right - left) / 2
            headerRightBound = inputLeftBound
            if (descriptionView.bottom > headerBottomBounds) {
                headerTopBounds -= iconView.bottom.coerceAtMost(bottomInset)
                credentialHeader.layout(left, headerTopBounds, headerRightBound, bottom)
            }
        } else {
            inputTopBound = descBottom + (bottom - descBottom - credentialInput.height) / 2
            inputLeftBound = (right - left - credentialInput.width) / 2

            if (bottom - inputTopBound < credentialInput.height) {
                inputTopBound = bottom - credentialInput.height
            }

            if (descriptionView.bottom > inputTopBound) {
                credentialHeader.layout(left, headerTopBounds, headerRightBound, inputTopBound)
            }
        }

        credentialInput.layout(inputLeftBound, inputTopBound, right, bottom)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val newWidth = MeasureSpec.getSize(widthMeasureSpec)
        val newHeight = MeasureSpec.getSize(heightMeasureSpec) - bottomInset

        setMeasuredDimension(newWidth, newHeight)

        val halfWidthSpec = MeasureSpec.makeMeasureSpec(width / 2, MeasureSpec.AT_MOST)
        val fullHeightSpec = MeasureSpec.makeMeasureSpec(newHeight, MeasureSpec.UNSPECIFIED)
        if (resources.configuration.orientation == ORIENTATION_LANDSCAPE) {
            measureChildren(halfWidthSpec, fullHeightSpec)
        } else {
            measureChildren(widthMeasureSpec, fullHeightSpec)
        }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsets): WindowInsets {
        val bottomInsets = insets.getInsets(ime())
        if (bottomInset != bottomInsets.bottom) {
            bottomInset = bottomInsets.bottom

            if (bottomInset > 0 && resources.configuration.orientation == ORIENTATION_LANDSCAPE) {
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

            requestLayout()
        }
        return insets
    }
}

private fun AccessibilityManager.shouldMarquee(): Boolean = !isEnabled || !isTouchExplorationEnabled

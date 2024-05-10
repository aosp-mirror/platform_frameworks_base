package com.android.systemui.keyguard.ui.view.layout.sections

import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import com.android.systemui.res.R
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder

abstract class BaseShortcutSection : KeyguardSection() {
    protected var leftShortcutHandle: KeyguardQuickAffordanceViewBinder.Binding? = null
    protected var rightShortcutHandle: KeyguardQuickAffordanceViewBinder.Binding? = null

    override fun removeViews(constraintLayout: ConstraintLayout) {
        leftShortcutHandle?.destroy()
        rightShortcutHandle?.destroy()
        constraintLayout.removeView(R.id.start_button)
        constraintLayout.removeView(R.id.end_button)
    }

    protected fun addLeftShortcut(constraintLayout: ConstraintLayout) {
        val padding =
            constraintLayout.resources.getDimensionPixelSize(
                R.dimen.keyguard_affordance_fixed_padding
            )
        val view =
            LaunchableImageView(constraintLayout.context, null).apply {
                id = R.id.start_button
                scaleType = ImageView.ScaleType.FIT_CENTER
                background =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_bg,
                        context.theme
                    )
                foreground =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_selected_border,
                        context.theme
                    )
                visibility = View.INVISIBLE
                setPadding(padding, padding, padding, padding)
            }
        constraintLayout.addView(view)
    }

    protected fun addRightShortcut(constraintLayout: ConstraintLayout) {
        if (constraintLayout.findViewById<View>(R.id.end_button) != null) return

        val padding =
            constraintLayout.resources.getDimensionPixelSize(
                R.dimen.keyguard_affordance_fixed_padding
            )
        val view =
            LaunchableImageView(constraintLayout.context, null).apply {
                id = R.id.end_button
                scaleType = ImageView.ScaleType.FIT_CENTER
                background =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_bg,
                        context.theme
                    )
                foreground =
                    ResourcesCompat.getDrawable(
                        context.resources,
                        R.drawable.keyguard_bottom_affordance_selected_border,
                        context.theme
                    )
                visibility = View.INVISIBLE
                setPadding(padding, padding, padding, padding)
            }
        constraintLayout.addView(view)
    }
    /**
     * Defines equality as same class.
     *
     * This is to enable set operations to be done as an optimization to blueprint transitions.
     */
    override fun equals(other: Any?): Boolean {
        return other is BaseShortcutSection
    }

    /**
     * Defines hashcode as class.
     *
     * This is to enable set operations to be done as an optimization to blueprint transitions.
     */
    override fun hashCode(): Int {
        return KEY.hashCode()
    }

    companion object {
        private const val KEY = "shortcuts"
    }
}

package com.android.systemui.communal.ui.view.layout.sections

import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.shared.model.KeyguardSection
import javax.inject.Inject

/** A keyguard section that hosts the communal hub. */
class DefaultCommunalHubSection @Inject constructor() : KeyguardSection() {
    override fun addViews(constraintLayout: ConstraintLayout) {}

    override fun bindData(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {}

    override fun removeViews(constraintLayout: ConstraintLayout) {}
}

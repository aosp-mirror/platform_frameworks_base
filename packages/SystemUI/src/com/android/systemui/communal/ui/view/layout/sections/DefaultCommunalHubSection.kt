package com.android.systemui.communal.ui.view.layout.sections

import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.compose.ComposeFacade
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.layout.sections.removeView
import com.android.systemui.res.R
import javax.inject.Inject

/** A keyguard section that hosts the communal hub. */
class DefaultCommunalHubSection
@Inject
constructor(
    private val viewModel: CommunalViewModel,
) : KeyguardSection() {
    private val communalHubViewId = R.id.communal_hub

    override fun addViews(constraintLayout: ConstraintLayout) {
        constraintLayout.addView(
            ComposeFacade.createCommunalView(
                    context = constraintLayout.context,
                    viewModel = viewModel,
                )
                .apply { id = communalHubViewId },
        )
    }

    override fun bindData(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            connect(
                communalHubViewId,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
            )
            connect(
                communalHubViewId,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
            )
            connect(
                communalHubViewId,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
            )
            connect(
                communalHubViewId,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM,
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.removeView(communalHubViewId)
    }
}

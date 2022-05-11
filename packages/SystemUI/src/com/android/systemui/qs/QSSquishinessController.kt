package com.android.systemui.qs

import com.android.systemui.qs.dagger.QSScope
import javax.inject.Inject

@QSScope
class QSSquishinessController @Inject constructor(
    private val qsAnimator: QSAnimator,
    private val qsPanelController: QSPanelController,
    private val quickQSPanelController: QuickQSPanelController
) {

    /**
     * Fraction from 0 to 1, where 0 is collapsed and 1 expanded.
     */
    var squishiness: Float = 1f
    set(value) {
        if (field == value) {
            return
        }
        if ((field != 1f && value == 1f) || (field != 0f && value == 0f)) {
            qsAnimator.requestAnimatorUpdate()
        }
        field = value
        updateSquishiness()
    }

    /**
     * Change the height of all tiles and repositions their siblings.
     */
    private fun updateSquishiness() {
        qsPanelController.setSquishinessFraction(squishiness)
        quickQSPanelController.setSquishinessFraction(squishiness)
    }
}
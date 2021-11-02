package com.android.systemui.qs

import android.view.ViewGroup
import com.android.systemui.qs.dagger.QSFragmentModule.QQS_FOOTER
import com.android.systemui.qs.dagger.QSScope
import com.android.systemui.qs.tileimpl.HeightOverrideable
import javax.inject.Inject
import javax.inject.Named

@QSScope
class QSSquishinessController @Inject constructor(
    private val qsTileHost: QSTileHost,
    @Named(QQS_FOOTER) private val qqsFooterActionsView: FooterActionsView,
    private val qsAnimator: QSAnimator,
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
        // Update tile positions in the layout
        val tileLayout = quickQSPanelController.tileLayout as TileLayout
        tileLayout.setSquishinessFraction(squishiness)

        // Adjust their heights as well
        for (tile in qsTileHost.tiles) {
            val tileView = quickQSPanelController.getTileView(tile)
            (tileView as? HeightOverrideable)?.let {
                it.squishinessFraction = squishiness
            }
        }

        // Calculate how much we should move the footer
        val tileHeightOffset = tileLayout.height - tileLayout.tilesHeight
        val footerTopMargin = (qqsFooterActionsView.layoutParams as ViewGroup.MarginLayoutParams)
                .topMargin
        val nextTop = tileLayout.bottom - tileHeightOffset + footerTopMargin
        val amountMoved = nextTop - qqsFooterActionsView.top

        // Move the footer and other siblings (MediaPlayer)
        (qqsFooterActionsView.parent as ViewGroup?)?.let { parent ->
            val index = parent.indexOfChild(qqsFooterActionsView)
            for (i in index until parent.childCount) {
                parent.getChildAt(i).top += amountMoved
            }
        }
    }
}
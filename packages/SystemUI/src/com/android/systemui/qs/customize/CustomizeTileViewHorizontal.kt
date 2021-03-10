package com.android.systemui.qs.customize

import android.content.Context
import android.view.View
import com.android.systemui.plugins.qs.QSIconView
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.tileimpl.QSTileViewHorizontal

/**
 * Class for displaying tiles in [QSCustomizer] with the new design (labels on the side).
 *
 * This is a class parallel to [CustomizeTileView], but inheriting from [QSTileViewHorizontal].
 */
class CustomizeTileViewHorizontal(
    context: Context,
    icon: QSIconView
) : QSTileViewHorizontal(context, icon),
    TileAdapter.CustomizeView {

    private var showAppLabel = false

    override fun setShowAppLabel(showAppLabel: Boolean) {
        this.showAppLabel = showAppLabel
        mSecondLine.visibility = if (showAppLabel) View.VISIBLE else View.GONE
        mLabel.isSingleLine = showAppLabel
    }

    override fun handleStateChanged(state: QSTile.State) {
        super.handleStateChanged(state)
        mSecondLine.visibility = if (showAppLabel) View.VISIBLE else View.GONE
    }

    override fun animationsEnabled(): Boolean {
        return false
    }

    override fun isLongClickable(): Boolean {
        return false
    }

    override fun changeState(state: QSTile.State) {
        handleStateChanged(state)
    }
}
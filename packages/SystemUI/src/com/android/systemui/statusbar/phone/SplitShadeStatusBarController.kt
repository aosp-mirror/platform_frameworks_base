package com.android.systemui.statusbar.phone

import android.view.View
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent.StatusBarScope
import com.android.systemui.statusbar.phone.dagger.StatusBarViewModule.SPLIT_SHADE_STATUS_BAR
import javax.inject.Inject
import javax.inject.Named

@StatusBarScope
class SplitShadeStatusBarController @Inject constructor(
    @Named(SPLIT_SHADE_STATUS_BAR) val view: View
) {

    var shadeExpanded = false
        set(value) {
            field = value
            updateVisibility()
        }

    var splitShadeMode = false
        set(value) {
            field = value
            updateVisibility()
        }

    private fun updateVisibility() {
        view.visibility = if (shadeExpanded && splitShadeMode) View.VISIBLE else View.GONE
    }
}
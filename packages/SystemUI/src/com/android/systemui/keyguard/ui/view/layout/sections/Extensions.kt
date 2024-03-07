package com.android.systemui.keyguard.ui.view.layout.sections

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout

internal fun ConstraintLayout.removeView(viewId: Int) {
    findViewById<View?>(viewId)?.let { removeView(it) }
}

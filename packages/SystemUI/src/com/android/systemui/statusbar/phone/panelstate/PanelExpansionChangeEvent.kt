package com.android.systemui.statusbar.phone.panelstate

import android.annotation.FloatRange

data class PanelExpansionChangeEvent(
    /** 0 when collapsed, 1 when fully expanded. */
    @FloatRange(from = 0.0, to = 1.0) val fraction: Float,
    /** Whether the panel should be considered expanded */
    val expanded: Boolean,
    /** Whether the user is actively dragging the panel. */
    val tracking: Boolean,
    /** The amount of pixels that the user has dragged during the expansion. */
    val dragDownPxAmount: Float
)

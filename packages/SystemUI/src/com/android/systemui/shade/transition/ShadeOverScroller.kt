package com.android.systemui.shade.transition

import com.android.systemui.shade.PanelState

/** Represents an over scroller for the non-lockscreen shade. */
interface ShadeOverScroller {

    fun onPanelStateChanged(@PanelState newPanelState: Int)

    fun onDragDownAmountChanged(newDragDownAmount: Float)
}

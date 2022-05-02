package com.android.systemui.statusbar.phone.shade.transition

import javax.inject.Inject

/**
 * An implementation on [ShadeOverScroller] that does nothing.
 *
 * At the moment there is only a concrete implementation [ShadeOverScroller] for split-shade, so
 * this one is used when we are not in split-shade.
 */
class NoOpOverScroller @Inject constructor() : ShadeOverScroller {
    override fun onPanelStateChanged(newPanelState: Int) {}
    override fun onDragDownAmountChanged(newDragDownAmount: Float) {}
}

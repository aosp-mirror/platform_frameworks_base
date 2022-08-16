package com.android.systemui.statusbar

/** Represents an over scroller for the transition to full shade on lock screen. */
interface LockScreenShadeOverScroller {

    /** The amount in pixels that the user has dragged to expand the shade. */
    var expansionDragDownAmount: Float
}
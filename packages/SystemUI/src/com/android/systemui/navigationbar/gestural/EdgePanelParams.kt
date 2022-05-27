package com.android.systemui.navigationbar.gestural

import android.content.res.Resources
import com.android.systemui.R

data class EdgePanelParams(private var resources: Resources) {
    var arrowThickness: Float = 0f
        private set
    var entryArrowLength: Float = 0f
        private set
    var entryArrowHeight: Float = 0f
        private set
    var activeArrowLength: Float = 0f
        private set
    var activeArrowHeight: Float = 0f
        private set
    var stretchedArrowLength: Float = 0f
        private set
    var stretchedArrowHeight: Float = 0f
        private set
    var cancelledArrowLength: Float = 0f
        private set
    var cancelledArrowHeight: Float = 0f
        private set
    var entryMargin: Float = 0f
        private set
    var entryBackgroundWidth: Float = 0f
        private set
    var entryBackgroundHeight: Float = 0f
        private set
    var entryEdgeCorners: Float = 0f
        private set
    var entryFarCorners: Float = 0f
        private set
    var preThresholdMargin: Float = 0f
        private set
    var preThresholdBackgroundWidth: Float = 0f
        private set
    var preThresholdBackgroundHeight: Float = 0f
        private set
    var preThresholdEdgeCorners: Float = 0f
        private set
    var preThresholdFarCorners: Float = 0f
        private set
    var activeMargin: Float = 0f
        private set
    var activeBackgroundWidth: Float = 0f
        private set
    var activeBackgroundHeight: Float = 0f
        private set
    var activeEdgeCorners: Float = 0f
        private set
    var activeFarCorners: Float = 0f
        private set
    var fullyStretchedThreshold: Float = 0f
        private set
    var stretchMargin: Float = 0f
        private set
    var stretchBackgroundWidth: Float = 0f
        private set
    var stretchBackgroundHeight: Float = 0f
        private set
    var stretchEdgeCorners: Float = 0f
        private set
    var stretchFarCorners: Float = 0f
        private set

    // navigation bar edge constants
    var arrowPaddingEnd: Int = 0
        private set

    // The closest to y
    var minArrowYPosition: Int = 0
        private set
    var fingerOffset: Int = 0
        private set
    var swipeTriggerThreshold: Float = 0f
        private set
    var swipeProgressThreshold: Float = 0f
        private set

    // The minimum delta needed to change direction / stop triggering back
    var minDeltaForSwitch: Int = 0
        private set

    init {
        update(resources)
    }

    private fun getDimen(id: Int): Float {
        return resources.getDimension(id)
    }

    private fun getPx(id: Int): Int {
        return resources.getDimensionPixelSize(id)
    }

    fun update(resources: Resources) {
        this.resources = resources
        arrowThickness = getDimen(R.dimen.navigation_edge_arrow_thickness)
        entryArrowLength = getDimen(R.dimen.navigation_edge_entry_arrow_length)
        entryArrowHeight = getDimen(R.dimen.navigation_edge_entry_arrow_height)
        activeArrowLength = getDimen(R.dimen.navigation_edge_active_arrow_length)
        activeArrowHeight = getDimen(R.dimen.navigation_edge_active_arrow_height)
        stretchedArrowLength = getDimen(R.dimen.navigation_edge_stretched_arrow_length)
        stretchedArrowHeight = getDimen(R.dimen.navigation_edge_stretched_arrow_height)
        cancelledArrowLength = getDimen(R.dimen.navigation_edge_cancelled_arrow_length)
        cancelledArrowHeight = getDimen(R.dimen.navigation_edge_cancelled_arrow_height)
        entryMargin = getDimen(R.dimen.navigation_edge_entry_margin)
        entryBackgroundWidth = getDimen(R.dimen.navigation_edge_entry_background_width)
        entryBackgroundHeight = getDimen(R.dimen.navigation_edge_entry_background_height)
        entryEdgeCorners = getDimen(R.dimen.navigation_edge_entry_edge_corners)
        entryFarCorners = getDimen(R.dimen.navigation_edge_entry_far_corners)
        preThresholdMargin = getDimen(R.dimen.navigation_edge_pre_threshold_margin)
        preThresholdBackgroundWidth =
            getDimen(R.dimen.navigation_edge_pre_threshold_background_width)
        preThresholdBackgroundHeight =
            getDimen(R.dimen.navigation_edge_pre_threshold_background_height)
        preThresholdEdgeCorners = getDimen(R.dimen.navigation_edge_pre_threshold_edge_corners)
        preThresholdFarCorners = getDimen(R.dimen.navigation_edge_pre_threshold_far_corners)
        activeMargin = getDimen(R.dimen.navigation_edge_active_margin)
        activeBackgroundWidth = getDimen(R.dimen.navigation_edge_active_background_width)
        activeBackgroundHeight = getDimen(R.dimen.navigation_edge_active_background_height)
        activeEdgeCorners = getDimen(R.dimen.navigation_edge_active_edge_corners)
        activeFarCorners = getDimen(R.dimen.navigation_edge_active_far_corners)
        fullyStretchedThreshold = getDimen(R.dimen.navigation_edge_stretch_threshold)
        stretchMargin = getDimen(R.dimen.navigation_edge_stretch_margin)
        stretchBackgroundWidth = getDimen(R.dimen.navigation_edge_stretch_background_width)
        stretchBackgroundHeight = getDimen(R.dimen.navigation_edge_stretch_background_height)
        stretchEdgeCorners = getDimen(R.dimen.navigation_edge_stretch_left_corners)
        stretchFarCorners = getDimen(R.dimen.navigation_edge_stretch_right_corners)
        arrowPaddingEnd = getPx(R.dimen.navigation_edge_panel_padding)
        minArrowYPosition = getPx(R.dimen.navigation_edge_arrow_min_y)
        fingerOffset = getPx(R.dimen.navigation_edge_finger_offset)
        swipeTriggerThreshold = getDimen(R.dimen.navigation_edge_action_drag_threshold)
        swipeProgressThreshold = getDimen(R.dimen.navigation_edge_action_progress_threshold)
        minDeltaForSwitch = getPx(R.dimen.navigation_edge_minimum_x_delta_for_switch)
    }
}

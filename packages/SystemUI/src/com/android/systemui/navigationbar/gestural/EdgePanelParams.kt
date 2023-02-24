package com.android.systemui.navigationbar.gestural

import android.content.res.Resources
import com.android.systemui.R

data class EdgePanelParams(private var resources: Resources) {

    data class ArrowDimens(
        val length: Float = 0f,
        val height: Float = 0f
    )

    data class BackgroundDimens(
        val width: Float = 0f,
        val height: Float = 0f,
        val edgeCornerRadius: Float = 0f,
        val farCornerRadius: Float = 0f
    )

    data class BackIndicatorDimens(
        val horizontalTranslation: Float = 0f,
        val arrowDimens: ArrowDimens = ArrowDimens(),
        val backgroundDimens: BackgroundDimens = BackgroundDimens()
    )

    var arrowThickness: Float = 0f
        private set
    var entryIndicator = BackIndicatorDimens()
        private set
    var activeIndicator = BackIndicatorDimens()
        private set
    var preThresholdIndicator = BackIndicatorDimens()
        private set
    var fullyStretchedIndicator = BackIndicatorDimens()
        private set
    var cancelledEdgeCornerRadius: Float = 0f
        private set
    var cancelledArrowDimens = ArrowDimens()

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
        arrowPaddingEnd = getPx(R.dimen.navigation_edge_panel_padding)
        minArrowYPosition = getPx(R.dimen.navigation_edge_arrow_min_y)
        fingerOffset = getPx(R.dimen.navigation_edge_finger_offset)
        swipeTriggerThreshold = getDimen(R.dimen.navigation_edge_action_drag_threshold)
        swipeProgressThreshold = getDimen(R.dimen.navigation_edge_action_progress_threshold)
        minDeltaForSwitch = getPx(R.dimen.navigation_edge_minimum_x_delta_for_switch)

        entryIndicator = BackIndicatorDimens(
            horizontalTranslation = getDimen(R.dimen.navigation_edge_entry_margin),
            arrowDimens = ArrowDimens(
                length = getDimen(R.dimen.navigation_edge_entry_arrow_length),
                height = getDimen(R.dimen.navigation_edge_entry_arrow_height),
            ),
            backgroundDimens = BackgroundDimens(
                width = getDimen(R.dimen.navigation_edge_entry_background_width),
                height = getDimen(R.dimen.navigation_edge_entry_background_height),
                edgeCornerRadius = getDimen(R.dimen.navigation_edge_entry_edge_corners),
                farCornerRadius = getDimen(R.dimen.navigation_edge_entry_far_corners)
            )
        )

        activeIndicator = BackIndicatorDimens(
            horizontalTranslation = getDimen(R.dimen.navigation_edge_active_margin),
            arrowDimens = ArrowDimens(
                length = getDimen(R.dimen.navigation_edge_active_arrow_length),
                height = getDimen(R.dimen.navigation_edge_active_arrow_height),
            ),
            backgroundDimens = BackgroundDimens(
                width = getDimen(R.dimen.navigation_edge_active_background_width),
                height = getDimen(R.dimen.navigation_edge_active_background_height),
                edgeCornerRadius = getDimen(R.dimen.navigation_edge_active_edge_corners),
                farCornerRadius = getDimen(R.dimen.navigation_edge_active_far_corners)

            )
        )

        preThresholdIndicator = BackIndicatorDimens(
            horizontalTranslation = getDimen(R.dimen.navigation_edge_pre_threshold_margin),
            arrowDimens = ArrowDimens(
                length = entryIndicator.arrowDimens.length,
                height = entryIndicator.arrowDimens.height,
            ),
            backgroundDimens = BackgroundDimens(
                width = getDimen(R.dimen.navigation_edge_pre_threshold_background_width),
                height = getDimen(R.dimen.navigation_edge_pre_threshold_background_height),
                edgeCornerRadius = getDimen(R.dimen.navigation_edge_pre_threshold_edge_corners),
                farCornerRadius = getDimen(R.dimen.navigation_edge_pre_threshold_far_corners)
            )
        )

        fullyStretchedIndicator = BackIndicatorDimens(
            horizontalTranslation = getDimen(R.dimen.navigation_edge_stretch_margin),
            arrowDimens = ArrowDimens(
                length = getDimen(R.dimen.navigation_edge_stretched_arrow_length),
                height = getDimen(R.dimen.navigation_edge_stretched_arrow_height),
            ),
            backgroundDimens = BackgroundDimens(
                width = getDimen(R.dimen.navigation_edge_stretch_background_width),
                height = getDimen(R.dimen.navigation_edge_stretch_background_height),
                edgeCornerRadius = getDimen(R.dimen.navigation_edge_stretch_edge_corners),
                farCornerRadius = getDimen(R.dimen.navigation_edge_stretch_far_corners)
            )
        )

        cancelledEdgeCornerRadius = getDimen(R.dimen.navigation_edge_cancelled_edge_corners)

        cancelledArrowDimens = ArrowDimens(
            length = getDimen(R.dimen.navigation_edge_cancelled_arrow_length),
            height = getDimen(R.dimen.navigation_edge_cancelled_arrow_height)
        )
    }
}

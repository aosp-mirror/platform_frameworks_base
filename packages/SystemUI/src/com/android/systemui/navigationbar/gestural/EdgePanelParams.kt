package com.android.systemui.navigationbar.gestural

import android.content.res.Resources
import android.util.TypedValue
import androidx.core.animation.Interpolator
import androidx.core.animation.PathInterpolator
import androidx.dynamicanimation.animation.SpringForce
import com.android.systemui.res.R

data class EdgePanelParams(private var resources: Resources) {

    data class ArrowDimens(
            val length: Float? = 0f,
            val height: Float? = 0f,
            val alpha: Float = 0f,
            val heightSpring: SpringForce? = null,
            val lengthSpring: SpringForce? = null,
            var alphaSpring: Step<SpringForce>? = null,
            var alphaInterpolator: Step<Float>? = null
    )

    data class BackgroundDimens(
            val width: Float? = 0f,
            val height: Float = 0f,
            val edgeCornerRadius: Float = 0f,
            val farCornerRadius: Float = 0f,
            val alpha: Float = 0f,
            val widthSpring: SpringForce? = null,
            val heightSpring: SpringForce? = null,
            val farCornerRadiusSpring: SpringForce? = null,
            val edgeCornerRadiusSpring: SpringForce? = null,
            val alphaSpring: SpringForce? = null,
    )

    data class BackIndicatorDimens(
            val horizontalTranslation: Float? = 0f,
            val scale: Float = 0f,
            val scalePivotX: Float? = null,
            val arrowDimens: ArrowDimens,
            val backgroundDimens: BackgroundDimens,
            val verticalTranslationSpring: SpringForce? = null,
            val horizontalTranslationSpring: SpringForce? = null,
            val scaleSpring: SpringForce? = null,
    )

    lateinit var entryIndicator: BackIndicatorDimens
        private set
    lateinit var activeIndicator: BackIndicatorDimens
        private set
    lateinit var cancelledIndicator: BackIndicatorDimens
        private set
    lateinit var flungIndicator: BackIndicatorDimens
        private set
    lateinit var committedIndicator: BackIndicatorDimens
        private set
    lateinit var preThresholdIndicator: BackIndicatorDimens
        private set
    lateinit var fullyStretchedIndicator: BackIndicatorDimens
        private set

    // navigation bar edge constants
    var arrowPaddingEnd: Int = 0
        private set
    var arrowThickness: Float = 0f
        private set
    // The closest to y
    var minArrowYPosition: Int = 0
        private set
    var fingerOffset: Int = 0
        private set
    var staticTriggerThreshold: Float = 0f
        private set
    var reactivationTriggerThreshold: Float = 0f
        private set
    var deactivationTriggerThreshold: Float = 0f
        get() = -field
        private set
    lateinit var dynamicTriggerThresholdRange: ClosedRange<Float>
        private set
    var swipeProgressThreshold: Float = 0f
        private set

    lateinit var entryWidthInterpolator: Interpolator
        private set
    lateinit var entryWidthTowardsEdgeInterpolator: Interpolator
        private set
    lateinit var activeWidthInterpolator: Interpolator
        private set
    lateinit var arrowAngleInterpolator: Interpolator
        private set
    lateinit var horizontalTranslationInterpolator: Interpolator
        private set
    lateinit var verticalTranslationInterpolator: Interpolator
        private set
    lateinit var farCornerInterpolator: Interpolator
        private set
    lateinit var edgeCornerInterpolator: Interpolator
        private set
    lateinit var heightInterpolator: Interpolator
        private set

    init {
        update(resources)
    }

    private fun getDimen(id: Int): Float {
        return resources.getDimension(id)
    }

    private fun getDimenFloat(id: Int): Float {
        return TypedValue().run { resources.getValue(id, this, true); float }
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
        staticTriggerThreshold = getDimen(R.dimen.navigation_edge_action_drag_threshold)
        reactivationTriggerThreshold =
                getDimen(R.dimen.navigation_edge_action_reactivation_drag_threshold)
        deactivationTriggerThreshold =
                getDimen(R.dimen.navigation_edge_action_deactivation_drag_threshold)
        dynamicTriggerThresholdRange =
                reactivationTriggerThreshold..deactivationTriggerThreshold
        swipeProgressThreshold = getDimen(R.dimen.navigation_edge_action_progress_threshold)

        entryWidthInterpolator = PathInterpolator(.19f, 1.27f, .71f, .86f)
        entryWidthTowardsEdgeInterpolator = PathInterpolator(1f, -3f, 1f, 1.2f)
        activeWidthInterpolator = PathInterpolator(.7f, -0.24f, .48f, 1.21f)
        arrowAngleInterpolator = entryWidthInterpolator
        horizontalTranslationInterpolator = PathInterpolator(0.2f, 1.0f, 1.0f, 1.0f)
        verticalTranslationInterpolator = PathInterpolator(.5f, 1.15f, .41f, .94f)
        farCornerInterpolator = PathInterpolator(.03f, .19f, .14f, 1.09f)
        edgeCornerInterpolator = PathInterpolator(0f, 1.11f, .85f, .84f)
        heightInterpolator = PathInterpolator(1f, .05f, .9f, -0.29f)

        val activeCommittedArrowLengthSpring = createSpring(1500f, 0.29f)
        val activeCommittedArrowHeightSpring = createSpring(1500f, 0.29f)
        val flungCommittedEdgeCornerSpring = createSpring(10000f, 1f)
        val flungCommittedFarCornerSpring = createSpring(10000f, 1f)
        val flungCommittedWidthSpring = createSpring(10000f, 1f)
        val flungCommittedHeightSpring = createSpring(10000f, 1f)

        val commonArrowDimensAlphaThreshold = .165f
        val commonArrowDimensAlphaFactor = 1.05f
        val commonArrowDimensAlphaSpring = Step(
            threshold = commonArrowDimensAlphaThreshold,
            factor = commonArrowDimensAlphaFactor,
            postThreshold = createSpring(180f, 0.9f),
            preThreshold = createSpring(2000f, 0.6f)
        )
        val commonArrowDimensAlphaSpringInterpolator = Step(
            threshold = commonArrowDimensAlphaThreshold,
            factor = commonArrowDimensAlphaFactor,
            postThreshold = 1f,
            preThreshold = 0f
        )

        entryIndicator = BackIndicatorDimens(
                horizontalTranslation = getDimen(R.dimen.navigation_edge_entry_margin),
                scale = getDimenFloat(R.dimen.navigation_edge_entry_scale),
                scalePivotX = getDimen(R.dimen.navigation_edge_pre_threshold_background_width),
                horizontalTranslationSpring = createSpring(800f, 0.76f),
                verticalTranslationSpring = createSpring(30000f, 1f),
                scaleSpring = createSpring(120f, 0.8f),
                arrowDimens = ArrowDimens(
                        length = getDimen(R.dimen.navigation_edge_entry_arrow_length),
                        height = getDimen(R.dimen.navigation_edge_entry_arrow_height),
                        alpha = 0f,
                        lengthSpring = createSpring(600f, 0.4f),
                        heightSpring = createSpring(600f, 0.4f),
                        alphaSpring = commonArrowDimensAlphaSpring,
                        alphaInterpolator = commonArrowDimensAlphaSpringInterpolator
                ),
                backgroundDimens = BackgroundDimens(
                        alpha = 1f,
                        width = getDimen(R.dimen.navigation_edge_entry_background_width),
                        height = getDimen(R.dimen.navigation_edge_entry_background_height),
                        edgeCornerRadius = getDimen(R.dimen.navigation_edge_entry_edge_corners),
                        farCornerRadius = getDimen(R.dimen.navigation_edge_entry_far_corners),
                        widthSpring = createSpring(450f, 0.65f),
                        heightSpring = createSpring(1500f, 0.45f),
                        farCornerRadiusSpring = createSpring(300f, 0.5f),
                        edgeCornerRadiusSpring = createSpring(150f, 0.5f),
                )
        )

        activeIndicator = BackIndicatorDimens(
                horizontalTranslation = getDimen(R.dimen.navigation_edge_active_margin),
                scale = getDimenFloat(R.dimen.navigation_edge_active_scale),
                horizontalTranslationSpring = createSpring(1000f, 0.8f),
                scaleSpring = createSpring(325f, 0.55f),
                scalePivotX = getDimen(R.dimen.navigation_edge_active_background_width),
                arrowDimens = ArrowDimens(
                        length = getDimen(R.dimen.navigation_edge_active_arrow_length),
                        height = getDimen(R.dimen.navigation_edge_active_arrow_height),
                        alpha = 1f,
                        lengthSpring = activeCommittedArrowLengthSpring,
                        heightSpring = activeCommittedArrowHeightSpring,
                        alphaSpring = commonArrowDimensAlphaSpring,
                        alphaInterpolator = commonArrowDimensAlphaSpringInterpolator
                ),
                backgroundDimens = BackgroundDimens(
                        alpha = 1f,
                        width = getDimen(R.dimen.navigation_edge_active_background_width),
                        height = getDimen(R.dimen.navigation_edge_active_background_height),
                        edgeCornerRadius = getDimen(R.dimen.navigation_edge_active_edge_corners),
                        farCornerRadius = getDimen(R.dimen.navigation_edge_active_far_corners),
                        widthSpring = createSpring(850f, 0.75f),
                        heightSpring = createSpring(10000f, 1f),
                        edgeCornerRadiusSpring = createSpring(2600f, 0.855f),
                        farCornerRadiusSpring = createSpring(1200f, 0.30f),
                )
        )

        preThresholdIndicator = BackIndicatorDimens(
                horizontalTranslation = getDimen(R.dimen.navigation_edge_pre_threshold_margin),
                scale = getDimenFloat(R.dimen.navigation_edge_pre_threshold_scale),
                scalePivotX = getDimen(R.dimen.navigation_edge_pre_threshold_background_width),
                scaleSpring = createSpring(120f, 0.8f),
                horizontalTranslationSpring = createSpring(6000f, 1f),
                arrowDimens = ArrowDimens(
                        length = getDimen(R.dimen.navigation_edge_pre_threshold_arrow_length),
                        height = getDimen(R.dimen.navigation_edge_pre_threshold_arrow_height),
                        alpha = 1f,
                        lengthSpring = createSpring(100f, 0.6f),
                        heightSpring = createSpring(100f, 0.6f),
                        alphaSpring = commonArrowDimensAlphaSpring,
                        alphaInterpolator = commonArrowDimensAlphaSpringInterpolator
                ),
                backgroundDimens = BackgroundDimens(
                        alpha = 1f,
                        width = getDimen(R.dimen.navigation_edge_pre_threshold_background_width),
                        height = getDimen(R.dimen.navigation_edge_pre_threshold_background_height),
                        edgeCornerRadius =
                                getDimen(R.dimen.navigation_edge_pre_threshold_edge_corners),
                        farCornerRadius =
                                getDimen(R.dimen.navigation_edge_pre_threshold_far_corners),
                        widthSpring = createSpring(650f, 1f),
                        heightSpring = createSpring(1500f, 0.45f),
                        farCornerRadiusSpring = createSpring(300f, 1f),
                        edgeCornerRadiusSpring = createSpring(250f, 0.5f),
                )
        )

        committedIndicator = activeIndicator.copy(
                horizontalTranslation = null,
                scalePivotX = null,
                arrowDimens = activeIndicator.arrowDimens.copy(
                        lengthSpring = activeCommittedArrowLengthSpring,
                        heightSpring = activeCommittedArrowHeightSpring,
                        length = null,
                        height = null,
                ),
                backgroundDimens = activeIndicator.backgroundDimens.copy(
                        alpha = 0f,
                        // explicitly set to null to preserve previous width upon state change
                        width = null,
                        widthSpring = flungCommittedWidthSpring,
                        heightSpring = flungCommittedHeightSpring,
                        edgeCornerRadiusSpring = flungCommittedEdgeCornerSpring,
                        farCornerRadiusSpring = flungCommittedFarCornerSpring,
                        alphaSpring = createSpring(1400f, 1f),
                ),
                scale = 0.86f,
                scaleSpring = createSpring(5700f, 1f),
        )

        flungIndicator = committedIndicator.copy(
                arrowDimens = committedIndicator.arrowDimens.copy(
                        lengthSpring = createSpring(850f, 0.46f),
                        heightSpring = createSpring(850f, 0.46f),
                        length = activeIndicator.arrowDimens.length,
                        height = activeIndicator.arrowDimens.height
                ),
                backgroundDimens = committedIndicator.backgroundDimens.copy(
                        widthSpring = flungCommittedWidthSpring,
                        heightSpring = flungCommittedHeightSpring,
                        edgeCornerRadiusSpring = flungCommittedEdgeCornerSpring,
                        farCornerRadiusSpring = flungCommittedFarCornerSpring,
                )
        )

        cancelledIndicator = entryIndicator.copy(
                backgroundDimens = entryIndicator.backgroundDimens.copy(
                        width = 0f,
                        alpha = 0f,
                        alphaSpring = createSpring(450f, 1f)
                )
        )

        fullyStretchedIndicator = BackIndicatorDimens(
                horizontalTranslation = getDimen(R.dimen.navigation_edge_stretch_margin),
                scale = getDimenFloat(R.dimen.navigation_edge_stretch_scale),
                horizontalTranslationSpring = null,
                verticalTranslationSpring = null,
                scaleSpring = null,
                arrowDimens = ArrowDimens(
                        length = getDimen(R.dimen.navigation_edge_stretched_arrow_length),
                        height = getDimen(R.dimen.navigation_edge_stretched_arrow_height),
                        alpha = 1f,
                        alphaSpring = null,
                        heightSpring = null,
                        lengthSpring = null,
                ),
                backgroundDimens = BackgroundDimens(
                        alpha = 1f,
                        width = getDimen(R.dimen.navigation_edge_stretch_background_width),
                        height = getDimen(R.dimen.navigation_edge_stretch_background_height),
                        edgeCornerRadius = getDimen(R.dimen.navigation_edge_stretch_edge_corners),
                        farCornerRadius = getDimen(R.dimen.navigation_edge_stretch_far_corners),
                        alphaSpring = null,
                        widthSpring = null,
                        heightSpring = null,
                        edgeCornerRadiusSpring = null,
                        farCornerRadiusSpring = null,
                )
        )
    }
}

fun createSpring(stiffness: Float, dampingRatio: Float): SpringForce {
    return SpringForce().setStiffness(stiffness).setDampingRatio(dampingRatio)
}
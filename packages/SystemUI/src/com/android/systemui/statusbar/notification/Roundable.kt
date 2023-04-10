package com.android.systemui.statusbar.notification

import android.util.FloatProperty
import android.view.View
import androidx.annotation.FloatRange
import com.android.systemui.R
import com.android.systemui.statusbar.notification.stack.AnimationProperties
import com.android.systemui.statusbar.notification.stack.StackStateAnimator
import kotlin.math.abs

/**
 * Interface that allows to request/retrieve top and bottom roundness (a value between 0f and 1f).
 *
 * To request a roundness value, an [SourceType] must be specified. In case more origins require
 * different roundness, for the same property, the maximum value will always be chosen.
 *
 * It also returns the current radius for all corners ([updatedRadii]).
 */
interface Roundable {
    /** Properties required for a Roundable */
    val roundableState: RoundableState

    /** Current top roundness */
    @get:FloatRange(from = 0.0, to = 1.0)
    @JvmDefault
    val topRoundness: Float
        get() = roundableState.topRoundness

    /** Current bottom roundness */
    @get:FloatRange(from = 0.0, to = 1.0)
    @JvmDefault
    val bottomRoundness: Float
        get() = roundableState.bottomRoundness

    /** Max radius in pixel */
    @JvmDefault
    val maxRadius: Float
        get() = roundableState.maxRadius

    /** Current top corner in pixel, based on [topRoundness] and [maxRadius] */
    @JvmDefault
    val topCornerRadius: Float
        get() = topRoundness * maxRadius

    /** Current bottom corner in pixel, based on [bottomRoundness] and [maxRadius] */
    @JvmDefault
    val bottomCornerRadius: Float
        get() = bottomRoundness * maxRadius

    /** Get and update the current radii */
    @JvmDefault
    val updatedRadii: FloatArray
        get() =
            roundableState.radiiBuffer.also { radii ->
                updateRadii(
                    topCornerRadius = topCornerRadius,
                    bottomCornerRadius = bottomCornerRadius,
                    radii = radii,
                )
            }

    /**
     * Request the top roundness [value] for a specific [sourceType].
     *
     * The top roundness of a [Roundable] can be defined by different [sourceType]. In case more
     * origins require different roundness, for the same property, the maximum value will always be
     * chosen.
     *
     * @param value a value between 0f and 1f.
     * @param animate true if it should animate to that value.
     * @param sourceType the source from which the request for roundness comes.
     * @return Whether the roundness was changed.
     */
    @JvmDefault
    fun requestTopRoundness(
        @FloatRange(from = 0.0, to = 1.0) value: Float,
        sourceType: SourceType,
        animate: Boolean,
    ): Boolean {
        val roundnessMap = roundableState.topRoundnessMap
        val lastValue = roundnessMap.values.maxOrNull() ?: 0f
        if (value == 0f) {
            // we should only take the largest value, and since the smallest value is 0f, we can
            // remove this value from the list. In the worst case, the list is empty and the
            // default value is 0f.
            roundnessMap.remove(sourceType)
        } else {
            roundnessMap[sourceType] = value
        }
        val newValue = roundnessMap.values.maxOrNull() ?: 0f

        if (lastValue != newValue) {
            val wasAnimating = roundableState.isTopAnimating()

            // Fail safe:
            // when we've been animating previously and we're now getting an update in the
            // other direction, make sure to animate it too, otherwise, the localized updating
            // may make the start larger than 1.0.
            val shouldAnimate = wasAnimating && abs(newValue - lastValue) > 0.5f

            roundableState.setTopRoundness(value = newValue, animated = shouldAnimate || animate)
            return true
        }
        return false
    }

    /**
     * Request the top roundness [value] for a specific [sourceType]. Animate the roundness if the
     * view is shown.
     *
     * The top roundness of a [Roundable] can be defined by different [sourceType]. In case more
     * origins require different roundness, for the same property, the maximum value will always be
     * chosen.
     *
     * @param value a value between 0f and 1f.
     * @param sourceType the source from which the request for roundness comes.
     * @return Whether the roundness was changed.
     */
    @JvmDefault
    fun requestTopRoundness(
        @FloatRange(from = 0.0, to = 1.0) value: Float,
        sourceType: SourceType,
    ): Boolean {
        return requestTopRoundness(
            value = value,
            sourceType = sourceType,
            animate = roundableState.targetView.isShown
        )
    }

    /**
     * Request the bottom roundness [value] for a specific [sourceType].
     *
     * The bottom roundness of a [Roundable] can be defined by different [sourceType]. In case more
     * origins require different roundness, for the same property, the maximum value will always be
     * chosen.
     *
     * @param value value between 0f and 1f.
     * @param animate true if it should animate to that value.
     * @param sourceType the source from which the request for roundness comes.
     * @return Whether the roundness was changed.
     */
    @JvmDefault
    fun requestBottomRoundness(
        @FloatRange(from = 0.0, to = 1.0) value: Float,
        sourceType: SourceType,
        animate: Boolean,
    ): Boolean {
        val roundnessMap = roundableState.bottomRoundnessMap
        val lastValue = roundnessMap.values.maxOrNull() ?: 0f
        if (value == 0f) {
            // we should only take the largest value, and since the smallest value is 0f, we can
            // remove this value from the list. In the worst case, the list is empty and the
            // default value is 0f.
            roundnessMap.remove(sourceType)
        } else {
            roundnessMap[sourceType] = value
        }
        val newValue = roundnessMap.values.maxOrNull() ?: 0f

        if (lastValue != newValue) {
            val wasAnimating = roundableState.isBottomAnimating()

            // Fail safe:
            // when we've been animating previously and we're now getting an update in the
            // other direction, make sure to animate it too, otherwise, the localized updating
            // may make the start larger than 1.0.
            val shouldAnimate = wasAnimating && abs(newValue - lastValue) > 0.5f

            roundableState.setBottomRoundness(value = newValue, animated = shouldAnimate || animate)
            return true
        }
        return false
    }

    /**
     * Request the bottom roundness [value] for a specific [sourceType]. Animate the roundness if
     * the view is shown.
     *
     * The bottom roundness of a [Roundable] can be defined by different [sourceType]. In case more
     * origins require different roundness, for the same property, the maximum value will always be
     * chosen.
     *
     * @param value value between 0f and 1f.
     * @param sourceType the source from which the request for roundness comes.
     * @return Whether the roundness was changed.
     */
    @JvmDefault
    fun requestBottomRoundness(
        @FloatRange(from = 0.0, to = 1.0) value: Float,
        sourceType: SourceType,
    ): Boolean {
        return requestBottomRoundness(
            value = value,
            sourceType = sourceType,
            animate = roundableState.targetView.isShown
        )
    }

    /**
     * Request the roundness [value] for a specific [sourceType].
     *
     * The top/bottom roundness of a [Roundable] can be defined by different [sourceType]. In case
     * more origins require different roundness, for the same property, the maximum value will
     * always be chosen.
     *
     * @param top top value between 0f and 1f.
     * @param bottom bottom value between 0f and 1f.
     * @param sourceType the source from which the request for roundness comes.
     * @param animate true if it should animate to that value.
     * @return Whether the roundness was changed.
     */
    @JvmDefault
    fun requestRoundness(
        @FloatRange(from = 0.0, to = 1.0) top: Float,
        @FloatRange(from = 0.0, to = 1.0) bottom: Float,
        sourceType: SourceType,
        animate: Boolean,
    ): Boolean {
        val hasTopChanged =
            requestTopRoundness(value = top, sourceType = sourceType, animate = animate)
        val hasBottomChanged =
            requestBottomRoundness(value = bottom, sourceType = sourceType, animate = animate)
        return hasTopChanged || hasBottomChanged
    }

    /**
     * Request the roundness [value] for a specific [sourceType]. Animate the roundness if the view
     * is shown.
     *
     * The top/bottom roundness of a [Roundable] can be defined by different [sourceType]. In case
     * more origins require different roundness, for the same property, the maximum value will
     * always be chosen.
     *
     * @param top top value between 0f and 1f.
     * @param bottom bottom value between 0f and 1f.
     * @param sourceType the source from which the request for roundness comes.
     * @return Whether the roundness was changed.
     */
    @JvmDefault
    fun requestRoundness(
        @FloatRange(from = 0.0, to = 1.0) top: Float,
        @FloatRange(from = 0.0, to = 1.0) bottom: Float,
        sourceType: SourceType,
    ): Boolean {
        return requestRoundness(
            top = top,
            bottom = bottom,
            sourceType = sourceType,
            animate = roundableState.targetView.isShown,
        )
    }

    /**
     * Request the roundness 0f for a [SourceType].
     *
     * The top/bottom roundness of a [Roundable] can be defined by different [sourceType]. In case
     * more origins require different roundness, for the same property, the maximum value will
     * always be chosen.
     *
     * @param sourceType the source from which the request for roundness comes.
     * @param animate true if it should animate to that value.
     */
    @JvmDefault
    fun requestRoundnessReset(sourceType: SourceType, animate: Boolean) {
        requestRoundness(top = 0f, bottom = 0f, sourceType = sourceType, animate = animate)
    }

    /**
     * Request the roundness 0f for a [SourceType]. Animate the roundness if the view is shown.
     *
     * The top/bottom roundness of a [Roundable] can be defined by different [sourceType]. In case
     * more origins require different roundness, for the same property, the maximum value will
     * always be chosen.
     *
     * @param sourceType the source from which the request for roundness comes.
     */
    @JvmDefault
    fun requestRoundnessReset(sourceType: SourceType) {
        requestRoundnessReset(sourceType = sourceType, animate = roundableState.targetView.isShown)
    }

    /** Apply the roundness changes, usually means invalidate the [RoundableState.targetView]. */
    @JvmDefault
    fun applyRoundnessAndInvalidate() {
        roundableState.targetView.invalidate()
    }

    /** @return true if top or bottom roundness is not zero. */
    @JvmDefault
    fun hasRoundedCorner(): Boolean {
        return topRoundness != 0f || bottomRoundness != 0f
    }

    /**
     * Update an Array of 8 values, 4 pairs of [X,Y] radii. As expected by param radii of
     * [android.graphics.Path.addRoundRect].
     *
     * This method reuses the previous [radii] for performance reasons.
     */
    @JvmDefault
    fun updateRadii(
        topCornerRadius: Float,
        bottomCornerRadius: Float,
        radii: FloatArray,
    ) {
        if (radii.size != 8) error("Unexpected radiiBuffer size ${radii.size}")

        if (radii[0] != topCornerRadius || radii[4] != bottomCornerRadius) {
            (0..3).forEach { radii[it] = topCornerRadius }
            (4..7).forEach { radii[it] = bottomCornerRadius }
        }
    }
}

/**
 * State object for a `Roundable` class.
 *
 * @param targetView Will handle the [AnimatableProperty]
 * @param roundable Target of the radius animation
 * @param maxRadius Max corner radius in pixels
 */
class RoundableState(
    internal val targetView: View,
    private val roundable: Roundable,
    maxRadius: Float,
) {
    internal var maxRadius = maxRadius
        private set

    /** Animatable for top roundness */
    private val topAnimatable = topAnimatable(roundable)

    /** Animatable for bottom roundness */
    private val bottomAnimatable = bottomAnimatable(roundable)

    /** Current top roundness. Use [setTopRoundness] to update this value */
    @set:FloatRange(from = 0.0, to = 1.0)
    internal var topRoundness = 0f
        private set

    /** Current bottom roundness. Use [setBottomRoundness] to update this value */
    @set:FloatRange(from = 0.0, to = 1.0)
    internal var bottomRoundness = 0f
        private set

    /** Last requested top roundness associated by [SourceType] */
    internal val topRoundnessMap = mutableMapOf<SourceType, Float>()

    /** Last requested bottom roundness associated by [SourceType] */
    internal val bottomRoundnessMap = mutableMapOf<SourceType, Float>()

    /** Last cached radii */
    internal val radiiBuffer = FloatArray(8)

    /** Is top roundness animation in progress? */
    internal fun isTopAnimating() = PropertyAnimator.isAnimating(targetView, topAnimatable)

    /** Is bottom roundness animation in progress? */
    internal fun isBottomAnimating() = PropertyAnimator.isAnimating(targetView, bottomAnimatable)

    /** Set the current top roundness */
    internal fun setTopRoundness(
        value: Float,
        animated: Boolean,
    ) {
        PropertyAnimator.setProperty(targetView, topAnimatable, value, DURATION, animated)
    }

    /** Set the current bottom roundness */
    internal fun setBottomRoundness(
        value: Float,
        animated: Boolean,
    ) {
        PropertyAnimator.setProperty(targetView, bottomAnimatable, value, DURATION, animated)
    }

    fun setMaxRadius(radius: Float) {
        if (maxRadius != radius) {
            maxRadius = radius
            roundable.applyRoundnessAndInvalidate()
        }
    }

    fun debugString() = buildString {
        append("TargetView: ${targetView.hashCode()} ")
        append("Top: $topRoundness ")
        append(topRoundnessMap.map { "${it.key} ${it.value}" })
        append(" Bottom: $bottomRoundness ")
        append(bottomRoundnessMap.map { "${it.key} ${it.value}" })
    }

    companion object {
        private val DURATION: AnimationProperties =
            AnimationProperties()
                .setDuration(StackStateAnimator.ANIMATION_DURATION_CORNER_RADIUS.toLong())

        private fun topAnimatable(roundable: Roundable): AnimatableProperty =
            AnimatableProperty.from(
                object : FloatProperty<View>("topRoundness") {
                    override fun get(view: View): Float = roundable.topRoundness

                    override fun setValue(view: View, value: Float) {
                        roundable.roundableState.topRoundness = value
                        roundable.applyRoundnessAndInvalidate()
                    }
                },
                R.id.top_roundess_animator_tag,
                R.id.top_roundess_animator_end_tag,
                R.id.top_roundess_animator_start_tag,
            )

        private fun bottomAnimatable(roundable: Roundable): AnimatableProperty =
            AnimatableProperty.from(
                object : FloatProperty<View>("bottomRoundness") {
                    override fun get(view: View): Float = roundable.bottomRoundness

                    override fun setValue(view: View, value: Float) {
                        roundable.roundableState.bottomRoundness = value
                        roundable.applyRoundnessAndInvalidate()
                    }
                },
                R.id.bottom_roundess_animator_tag,
                R.id.bottom_roundess_animator_end_tag,
                R.id.bottom_roundess_animator_start_tag,
            )
    }
}

/**
 * Interface used to define the owner of a roundness. Usually the [SourceType] is defined as a
 * private property of a class.
 */
interface SourceType {
    companion object {
        /**
         * This is the most convenient way to define a new [SourceType].
         *
         * For example:
         * ```kotlin
         *     private val SECTION = SourceType.from("Section")
         * ```
         */
        @JvmStatic
        fun from(name: String) =
            object : SourceType {
                override fun toString() = name
            }
    }
}

@Deprecated("Use SourceType.from() instead", ReplaceWith("SourceType.from()"))
enum class LegacySourceType : SourceType {
    DefaultValue,
    OnDismissAnimation,
    OnScroll,
}

package com.android.systemui.media

import android.graphics.Rect
import android.util.MathUtils
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewGroup
import com.android.systemui.media.MediaHierarchyManager.MediaLocation
import com.android.systemui.util.animation.MeasurementInput
import com.android.systemui.util.animation.MeasurementInputData
import javax.inject.Inject

class MediaHost @Inject constructor(
    private val state: MediaHostState,
    private val mediaHierarchyManager: MediaHierarchyManager,
    private val mediaDataManager: MediaDataManager
) : MediaState by state {
    lateinit var hostView: ViewGroup
    var location: Int = -1
        private set
    var visibleChangedListener: ((Boolean) -> Unit)? = null
    var visible: Boolean = false
        private set

    private val tmpLocationOnScreen: IntArray = intArrayOf(0, 0)

    /**
     * Get the current Media state. This also updates the location on screen
     */
    val currentState : MediaState
        get () {
            hostView.getLocationOnScreen(tmpLocationOnScreen)
            var left = tmpLocationOnScreen[0] + hostView.paddingLeft
            var top = tmpLocationOnScreen[1] + hostView.paddingTop
            var right = tmpLocationOnScreen[0] + hostView.width - hostView.paddingRight
            var bottom = tmpLocationOnScreen[1] + hostView.height - hostView.paddingBottom
            // Handle cases when the width or height is 0 but it has padding. In those cases
            // the above could return negative widths, which is wrong
            if (right < left) {
                left = 0
                right = 0;
            }
            if (bottom < top) {
                bottom = 0
                top = 0;
            }
            state.boundsOnScreen.set(left, top, right, bottom)
            return state
        }

    private val listener = object : MediaDataManager.Listener {
        override fun onMediaDataLoaded(key: String, data: MediaData) {
            updateViewVisibility()
        }

        override fun onMediaDataRemoved(key: String) {
            updateViewVisibility()
        }
    }

    /**
     * Initialize this MediaObject and create a host view
     */
    fun init(@MediaLocation location: Int) {
        this.location = location;
        hostView = mediaHierarchyManager.register(this)
        hostView.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View?) {
                mediaDataManager.addListener(listener)
                updateViewVisibility()
            }

            override fun onViewDetachedFromWindow(v: View?) {
                mediaDataManager.removeListener(listener)
            }
        })
        updateViewVisibility()
    }

    private fun updateViewVisibility() {
        if (showsOnlyActiveMedia) {
            visible = mediaDataManager.hasActiveMedia()
        } else {
            visible = mediaDataManager.hasAnyMedia()
        }
        hostView.visibility = if (visible) View.VISIBLE else View.GONE
        visibleChangedListener?.invoke(visible)
    }

    class MediaHostState @Inject constructor() : MediaState {
        var measurementInput: MediaMeasurementInput? = null
        override var expansion: Float = 0.0f
        override var showsOnlyActiveMedia: Boolean = false
        override val boundsOnScreen: Rect = Rect()

        override fun copy() : MediaState {
            val mediaHostState = MediaHostState()
            mediaHostState.expansion = expansion
            mediaHostState.showsOnlyActiveMedia = showsOnlyActiveMedia
            mediaHostState.boundsOnScreen.set(boundsOnScreen)
            mediaHostState.measurementInput = measurementInput
            return mediaHostState
        }

        override fun interpolate(other: MediaState, amount: Float) : MediaState {
            val result = MediaHostState()
            result.expansion = MathUtils.lerp(expansion, other.expansion, amount)
            val left = MathUtils.lerp(boundsOnScreen.left.toFloat(),
                    other.boundsOnScreen.left.toFloat(), amount).toInt()
            val top = MathUtils.lerp(boundsOnScreen.top.toFloat(),
                    other.boundsOnScreen.top.toFloat(), amount).toInt()
            val right = MathUtils.lerp(boundsOnScreen.right.toFloat(),
                    other.boundsOnScreen.right.toFloat(), amount).toInt()
            val bottom = MathUtils.lerp(boundsOnScreen.bottom.toFloat(),
                    other.boundsOnScreen.bottom.toFloat(), amount).toInt()
            result.boundsOnScreen.set(left, top, right, bottom)
            result.showsOnlyActiveMedia = other.showsOnlyActiveMedia || showsOnlyActiveMedia
            if (amount > 0.0f) {
                if (other is MediaHostState) {
                    result.measurementInput = other.measurementInput
                }
            }  else {
                result.measurementInput
            }
            return  result
        }

        override fun getMeasuringInput(input: MeasurementInput): MediaMeasurementInput {
            measurementInput = MediaMeasurementInput(input, expansion)
            return measurementInput as MediaMeasurementInput
        }
    }
}

interface MediaState {
    var expansion: Float
    var showsOnlyActiveMedia: Boolean
    val boundsOnScreen: Rect
    fun copy() : MediaState
    fun interpolate(other: MediaState, amount: Float) : MediaState
    fun getMeasuringInput(input: MeasurementInput): MediaMeasurementInput
}
/**
 * The measurement input for a Media View
 */
data class MediaMeasurementInput(
    private val viewInput: MeasurementInput,
    val expansion: Float) : MeasurementInput by viewInput {

    override fun sameAs(input: MeasurementInput?): Boolean {
        if (!(input is MediaMeasurementInput)) {
            return false
        }
        return width == input.width && expansion == input.expansion
    }
}


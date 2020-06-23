package com.android.systemui.media

import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import android.view.View.OnAttachStateChangeListener
import com.android.systemui.util.animation.MeasurementInput
import com.android.systemui.util.animation.MeasurementOutput
import com.android.systemui.util.animation.UniqueObjectHostView
import java.util.Objects
import javax.inject.Inject

class MediaHost @Inject constructor(
    private val state: MediaHostStateHolder,
    private val mediaHierarchyManager: MediaHierarchyManager,
    private val mediaDataManager: MediaDataManager,
    private val mediaDataManagerCombineLatest: MediaDataCombineLatest,
    private val mediaHostStatesManager: MediaHostStatesManager
) : MediaHostState by state {
    lateinit var hostView: UniqueObjectHostView
    var location: Int = -1
        private set
    var visibleChangedListener: ((Boolean) -> Unit)? = null

    private val tmpLocationOnScreen: IntArray = intArrayOf(0, 0)

    /**
     * Get the current bounds on the screen. This makes sure the state is fresh and up to date
     */
    val currentBounds: Rect = Rect()
        get() {
            hostView.getLocationOnScreen(tmpLocationOnScreen)
            var left = tmpLocationOnScreen[0] + hostView.paddingLeft
            var top = tmpLocationOnScreen[1] + hostView.paddingTop
            var right = tmpLocationOnScreen[0] + hostView.width - hostView.paddingRight
            var bottom = tmpLocationOnScreen[1] + hostView.height - hostView.paddingBottom
            // Handle cases when the width or height is 0 but it has padding. In those cases
            // the above could return negative widths, which is wrong
            if (right < left) {
                left = 0
                right = 0
            }
            if (bottom < top) {
                bottom = 0
                top = 0
            }
            field.set(left, top, right, bottom)
            return field
        }

    private val listener = object : MediaDataManager.Listener {
        override fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {
            updateViewVisibility()
        }

        override fun onMediaDataRemoved(key: String) {
            updateViewVisibility()
        }
    }

    /**
     * Initialize this MediaObject and create a host view.
     * All state should already be set on this host before calling this method in order to avoid
     * unnecessary state changes which lead to remeasurings later on.
     *
     * @param location the location this host name has. Used to identify the host during
     *                 transitions.
     */
    fun init(@MediaLocation location: Int) {
        this.location = location
        hostView = mediaHierarchyManager.register(this)
        hostView.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View?) {
                // we should listen to the combined state change, since otherwise there might
                // be a delay until the views and the controllers are initialized, leaving us
                // with either a blank view or the controllers not yet initialized and the
                // measuring wrong
                mediaDataManagerCombineLatest.addListener(listener)
                updateViewVisibility()
            }

            override fun onViewDetachedFromWindow(v: View?) {
                mediaDataManagerCombineLatest.removeListener(listener)
            }
        })

        // Listen to measurement updates and update our state with it
        hostView.measurementManager = object : UniqueObjectHostView.MeasurementManager {
            override fun onMeasure(input: MeasurementInput): MeasurementOutput {
                // Modify the measurement to exactly match the dimensions
                if (View.MeasureSpec.getMode(input.widthMeasureSpec) == View.MeasureSpec.AT_MOST) {
                    input.widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                            View.MeasureSpec.getSize(input.widthMeasureSpec),
                            View.MeasureSpec.EXACTLY)
                }
                // This will trigger a state change that ensures that we now have a state available
                state.measurementInput = input
                return mediaHostStatesManager.getPlayerDimensions(state)
            }
        }

        // Whenever the state changes, let our state manager know
        state.changedListener = {
            mediaHostStatesManager.updateHostState(location, state)
        }

        updateViewVisibility()
    }

    private fun updateViewVisibility() {
        visible = if (showsOnlyActiveMedia) {
            mediaDataManager.hasActiveMedia()
        } else {
            mediaDataManager.hasAnyMedia()
        }
        val newVisibility = if (visible) View.VISIBLE else View.GONE
        if (newVisibility != hostView.visibility) {
            hostView.visibility = newVisibility
            visibleChangedListener?.invoke(visible)
        }
    }

    class MediaHostStateHolder @Inject constructor() : MediaHostState {
        private var gonePivot: PointF = PointF()

        override var measurementInput: MeasurementInput? = null
            set(value) {
                if (value?.equals(field) != true) {
                    field = value
                    changedListener?.invoke()
                }
            }

        override var expansion: Float = 0.0f
            set(value) {
                if (!value.equals(field)) {
                    field = value
                    changedListener?.invoke()
                }
            }

        override var showsOnlyActiveMedia: Boolean = false
            set(value) {
                if (!value.equals(field)) {
                    field = value
                    changedListener?.invoke()
                }
            }

        override var visible: Boolean = true
            set(value) {
                if (field == value) {
                    return
                }
                field = value
                changedListener?.invoke()
            }

        override var falsingProtectionNeeded: Boolean = false
            set(value) {
                if (field == value) {
                    return
                }
                field = value
                changedListener?.invoke()
            }

        override fun getPivotX(): Float = gonePivot.x
        override fun getPivotY(): Float = gonePivot.y
        override fun setGonePivot(x: Float, y: Float) {
            if (gonePivot.equals(x, y)) {
                return
            }
            gonePivot.set(x, y)
            changedListener?.invoke()
        }

        /**
         * A listener for all changes. This won't be copied over when invoking [copy]
         */
        var changedListener: (() -> Unit)? = null

        /**
         * Get a copy of this state. This won't copy any listeners it may have set
         */
        override fun copy(): MediaHostState {
            val mediaHostState = MediaHostStateHolder()
            mediaHostState.expansion = expansion
            mediaHostState.showsOnlyActiveMedia = showsOnlyActiveMedia
            mediaHostState.measurementInput = measurementInput?.copy()
            mediaHostState.visible = visible
            mediaHostState.gonePivot.set(gonePivot)
            mediaHostState.falsingProtectionNeeded = falsingProtectionNeeded
            return mediaHostState
        }

        override fun equals(other: Any?): Boolean {
            if (!(other is MediaHostState)) {
                return false
            }
            if (!Objects.equals(measurementInput, other.measurementInput)) {
                return false
            }
            if (expansion != other.expansion) {
                return false
            }
            if (showsOnlyActiveMedia != other.showsOnlyActiveMedia) {
                return false
            }
            if (visible != other.visible) {
                return false
            }
            if (falsingProtectionNeeded != other.falsingProtectionNeeded) {
                return false
            }
            if (!gonePivot.equals(other.getPivotX(), other.getPivotY())) {
                return false
            }
            return true
        }

        override fun hashCode(): Int {
            var result = measurementInput?.hashCode() ?: 0
            result = 31 * result + expansion.hashCode()
            result = 31 * result + falsingProtectionNeeded.hashCode()
            result = 31 * result + showsOnlyActiveMedia.hashCode()
            result = 31 * result + if (visible) 1 else 2
            result = 31 * result + gonePivot.hashCode()
            return result
        }
    }
}

interface MediaHostState {

    /**
     * The last measurement input that this state was measured with. Infers with and height of
     * the players.
     */
    var measurementInput: MeasurementInput?

    /**
     * The expansion of the player, 0 for fully collapsed (up to 3 actions), 1 for fully expanded
     * (up to 5 actions.)
     */
    var expansion: Float

    /**
     * Is this host only showing active media or is it showing all of them including resumption?
     */
    var showsOnlyActiveMedia: Boolean

    /**
     * If the view should be VISIBLE or GONE.
     */
    var visible: Boolean

    /**
     * Does this host need any falsing protection?
     */
    var falsingProtectionNeeded: Boolean

    /**
     * Sets the pivot point when clipping the height or width.
     * Clipping happens when animating visibility when we're visible in QS but not on QQS,
     * for example.
     */
    fun setGonePivot(x: Float, y: Float)

    /**
     * x position of pivot, from 0 to 1
     * @see [setGonePivot]
     */
    fun getPivotX(): Float

    /**
     * y position of pivot, from 0 to 1
     * @see [setGonePivot]
     */
    fun getPivotY(): Float

    /**
     * Get a copy of this view state, deepcopying all appropriate members
     */
    fun copy(): MediaHostState
}
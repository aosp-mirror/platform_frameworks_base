package com.android.systemui.media

import android.graphics.Rect
import android.util.ArraySet
import android.view.View
import android.view.View.OnAttachStateChangeListener
import com.android.systemui.util.animation.DisappearParameters
import com.android.systemui.util.animation.MeasurementInput
import com.android.systemui.util.animation.MeasurementOutput
import com.android.systemui.util.animation.UniqueObjectHostView
import java.util.Objects
import javax.inject.Inject

class MediaHost constructor(
    private val state: MediaHostStateHolder,
    private val mediaHierarchyManager: MediaHierarchyManager,
    private val mediaDataManager: MediaDataManager,
    private val mediaHostStatesManager: MediaHostStatesManager
) : MediaHostState by state {
    lateinit var hostView: UniqueObjectHostView
    var location: Int = -1
        private set
    private var visibleChangedListeners: ArraySet<(Boolean) -> Unit> = ArraySet()

    private val tmpLocationOnScreen: IntArray = intArrayOf(0, 0)

    private var inited: Boolean = false

    /**
     * Are we listening to media data changes?
     */
    private var listeningToMediaData = false

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
        override fun onMediaDataLoaded(
            key: String,
            oldKey: String?,
            data: MediaData,
            immediately: Boolean,
            isSsReactivated: Boolean
        ) {
            if (immediately) {
                updateViewVisibility()
            }
        }

        override fun onSmartspaceMediaDataLoaded(
            key: String,
            data: SmartspaceMediaData,
            shouldPrioritize: Boolean
        ) {
            updateViewVisibility()
        }

        override fun onMediaDataRemoved(key: String) {
            updateViewVisibility()
        }

        override fun onSmartspaceMediaDataRemoved(key: String, immediately: Boolean) {
            if (immediately) {
                updateViewVisibility()
            }
        }
    }

    fun addVisibilityChangeListener(listener: (Boolean) -> Unit) {
        visibleChangedListeners.add(listener)
    }

    fun removeVisibilityChangeListener(listener: (Boolean) -> Unit) {
        visibleChangedListeners.remove(listener)
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
        if (inited) {
            return
        }
        inited = true

        this.location = location
        hostView = mediaHierarchyManager.register(this)
        // Listen by default, as the host might not be attached by our clients, until
        // they get a visibility change. We still want to stay up to date in that case!
        setListeningToMediaData(true)
        hostView.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View?) {
                setListeningToMediaData(true)
                updateViewVisibility()
            }

            override fun onViewDetachedFromWindow(v: View?) {
                setListeningToMediaData(false)
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
                return mediaHostStatesManager.updateCarouselDimensions(location, state)
            }
        }

        // Whenever the state changes, let our state manager know
        state.changedListener = {
            mediaHostStatesManager.updateHostState(location, state)
        }

        updateViewVisibility()
    }

    private fun setListeningToMediaData(listen: Boolean) {
        if (listen != listeningToMediaData) {
            listeningToMediaData = listen
            if (listen) {
                mediaDataManager.addListener(listener)
            } else {
                mediaDataManager.removeListener(listener)
            }
        }
    }

    private fun updateViewVisibility() {
        state.visible = if (showsOnlyActiveMedia) {
            mediaDataManager.hasActiveMedia()
        } else {
            mediaDataManager.hasAnyMedia()
        }
        val newVisibility = if (visible) View.VISIBLE else View.GONE
        if (newVisibility != hostView.visibility) {
            hostView.visibility = newVisibility
            visibleChangedListeners.forEach {
                it.invoke(visible)
            }
        }
    }

    class MediaHostStateHolder @Inject constructor() : MediaHostState {
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

        override var disappearParameters: DisappearParameters = DisappearParameters()
            set(value) {
                val newHash = value.hashCode()
                if (lastDisappearHash.equals(newHash)) {
                    return
                }
                field = value
                lastDisappearHash = newHash
                changedListener?.invoke()
            }

        private var lastDisappearHash = disappearParameters.hashCode()

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
            mediaHostState.disappearParameters = disappearParameters.deepCopy()
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
            if (!disappearParameters.equals(other.disappearParameters)) {
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
            result = 31 * result + disappearParameters.hashCode()
            return result
        }
    }
}

/**
 * A description of a media host state that describes the behavior whenever the media carousel
 * is hosted. The HostState notifies the media players of changes to their properties, who
 * in turn will create view states from it.
 * When adding a new property to this, make sure to update the listener and notify them
 * about the changes.
 * In case you need to have a different rendering based on the state, you can add a new
 * constraintState to the [MediaViewController]. Otherwise, similar host states will resolve
 * to the same viewstate, a behavior that is described in [CacheKey]. Make sure to only update
 * that key if the underlying view needs to have a different measurement.
 */
interface MediaHostState {

    companion object {
        const val EXPANDED: Float = 1.0f
        const val COLLAPSED: Float = 0.0f
    }

    /**
     * The last measurement input that this state was measured with. Infers width and height of
     * the players.
     */
    var measurementInput: MeasurementInput?

    /**
     * The expansion of the player, [COLLAPSED] for fully collapsed (up to 3 actions),
     * [EXPANDED] for fully expanded (up to 5 actions).
     */
    var expansion: Float

    /**
     * Is this host only showing active media or is it showing all of them including resumption?
     */
    var showsOnlyActiveMedia: Boolean

    /**
     * If the view should be VISIBLE or GONE.
     */
    val visible: Boolean

    /**
     * Does this host need any falsing protection?
     */
    var falsingProtectionNeeded: Boolean

    /**
     * The parameters how the view disappears from this location when going to a host that's not
     * visible. If modified, make sure to set this value again on the host to ensure the values
     * are propagated
     */
    var disappearParameters: DisappearParameters

    /**
     * Get a copy of this view state, deepcopying all appropriate members
     */
    fun copy(): MediaHostState
}

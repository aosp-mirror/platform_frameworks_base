/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.IntDef
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroupOverlay
import com.android.systemui.Interpolators
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.StackStateAnimator
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.animation.UniqueObjectHostView
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This manager is responsible for placement of the unique media view between the different hosts
 * and animate the positions of the views to achieve seamless transitions.
 */
@Singleton
class MediaHierarchyManager @Inject constructor(
    private val context: Context,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val bypassController: KeyguardBypassController,
    private val mediaViewManager: MediaViewManager,
    private val mediaMeasurementProvider: MediaMeasurementManager,
    private val notifLockscreenUserManager: NotificationLockscreenUserManager
) {
    /**
     * The root overlay of the hierarchy. This is where the media notification is attached to
     * whenever the view is transitioning from one host to another. It also make sure that the
     * view is always in its final state when it is attached to a view host.
     */
    private var rootOverlay: ViewGroupOverlay? = null
    private lateinit var currentState: MediaState
    private val mediaCarousel
        get() = mediaViewManager.mediaFrame
    private var animationStartState: MediaState? = null
    private var statusbarState: Int = statusBarStateController.state
    private var animator = ValueAnimator.ofFloat(0.0f, 1.0f).apply {
        interpolator = Interpolators.FAST_OUT_SLOW_IN
        addUpdateListener {
            updateTargetState()
            applyState(animationStartState!!.interpolate(targetState!!, animatedFraction))
        }
        addListener(object : AnimatorListenerAdapter() {
            private var cancelled: Boolean = false

            override fun onAnimationCancel(animation: Animator?) {
                cancelled = true
            }
            override fun onAnimationEnd(animation: Animator?) {
                if (!cancelled) {
                    applyTargetStateIfNotAnimating()
                }
            }

            override fun onAnimationStart(animation: Animator?) {
                cancelled = false
            }
        })
    }
    private var targetState: MediaState? = null
    private val mediaHosts = arrayOfNulls<MediaHost>(LOCATION_LOCKSCREEN + 1)

    /**
     * The last location where this view was at before going to the desired location. This is
     * useful for guided transitions.
     */
    @MediaLocation private var previousLocation = -1

    /**
     * The desired location where the view will be at the end of the transition.
     */
    @MediaLocation private var desiredLocation = -1

    /**
     * The current attachment location where the view is currently attached.
     * Usually this matches the desired location except for animations whenever a view moves
     * to the new desired location, during which it is in [IN_OVERLAY].
     */
    @MediaLocation private var currentAttachmentLocation = -1

    var qsExpansion: Float = 0.0f
        set(value) {
            if (field != value) {
                field = value
                updateDesiredLocation()
                if (getQSTransformationProgress() >= 0) {
                    updateTargetState()
                    applyTargetStateIfNotAnimating()
                }
            }
        }

    init {
        statusBarStateController.addCallback(object : StatusBarStateController.StateListener {
            override fun onStatePreChange(oldState: Int, newState: Int) {
                // We're updating the location before the state change happens, since we want the
                // location of the previous state to still be up to date when the animation starts
                statusbarState = newState
                updateDesiredLocation()
            }

            override fun onStateChanged(newState: Int) {
                updateTargetState()
            }
        })
    }

    /**
     * Register a media host and create a view can be attached to a view hierarchy
     * and where the players will be placed in when the host is the currently desired state.
     *
     * @return the hostView associated with this location
     */
    fun register(mediaObject: MediaHost): ViewGroup {
        val viewHost = createUniqueObjectHost(mediaObject)
        mediaObject.hostView = viewHost
        mediaHosts[mediaObject.location] = mediaObject
        if (mediaObject.location == desiredLocation) {
            // In case we are overriding a view that is already visible, make sure we attach it
            // to this new host view in the below call
            desiredLocation = -1
        }
        if (mediaObject.location == currentAttachmentLocation) {
            currentAttachmentLocation = -1
        }
        updateDesiredLocation()
        return viewHost
    }

    private fun createUniqueObjectHost(host: MediaHost): UniqueObjectHostView {
        val viewHost = UniqueObjectHostView(context)
        viewHost.measurementCache = mediaMeasurementProvider.obtainCache(host)
        viewHost.onMeasureListener = { input ->
            if (host.location == desiredLocation) {
                // Measurement of the currently active player is happening, Let's make
                // sure the player width is up to date
                val measuringInput = host.getMeasuringInput(input)
                mediaViewManager.setPlayerWidth(measuringInput.width)
            }
        }

        viewHost.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(p0: View?) {
                if (rootOverlay == null) {
                    rootOverlay = (viewHost.viewRootImpl.view.overlay as ViewGroupOverlay)
                }
                viewHost.removeOnAttachStateChangeListener(this)
            }

            override fun onViewDetachedFromWindow(p0: View?) {
            }
        })
        return viewHost
    }

    /**
     * Updates the location that the view should be in. If it changes, an animation may be triggered
     * going from the old desired location to the new one.
     */
    private fun updateDesiredLocation() {
        val desiredLocation = calculateLocation()
        if (desiredLocation != this.desiredLocation) {
            if (this.desiredLocation >= 0) {
                previousLocation = this.desiredLocation
            }
            val isNewView = this.desiredLocation == -1
            this.desiredLocation = desiredLocation
            // Let's perform a transition
            val animate = shouldAnimateTransition(desiredLocation, previousLocation)
            val (animDuration, delay) = getAnimationParams(previousLocation, desiredLocation)
            mediaViewManager.onDesiredLocationChanged(getHost(desiredLocation)?.currentState,
                    animate, animDuration, delay)
            performTransitionToNewLocation(isNewView, animate)
        }
    }

    private fun performTransitionToNewLocation(isNewView: Boolean, animate: Boolean) {
        if (previousLocation < 0 || isNewView) {
            cancelAnimationAndApplyDesiredState()
            return
        }
        val currentHost = getHost(desiredLocation)
        val previousHost = getHost(previousLocation)
        if (currentHost == null || previousHost == null) {
            cancelAnimationAndApplyDesiredState()
            return
        }
        updateTargetState()
        if (isCurrentlyInGuidedTransformation()) {
            applyTargetStateIfNotAnimating()
        } else if (animate) {
            animator.cancel()
            if (currentAttachmentLocation == IN_OVERLAY ||
                    !previousHost.hostView.isAttachedToWindow) {
                // Let's animate to the new position, starting from the current position
                // We also go in here in case the view was detached, since the bounds wouldn't
                // be correct anymore
                animationStartState = currentState.copy()
            } else {
                // otherwise, let's take the freshest state, since the current one could
                // be outdated
                animationStartState = previousHost.currentState.copy()
            }
            adjustAnimatorForTransition(desiredLocation, previousLocation)
            animator.start()
        } else {
            cancelAnimationAndApplyDesiredState()
        }
    }

    private fun shouldAnimateTransition(
        @MediaLocation currentLocation: Int,
        @MediaLocation previousLocation: Int
    ): Boolean {
        if (currentLocation == LOCATION_QQS &&
                previousLocation == LOCATION_LOCKSCREEN &&
                (statusBarStateController.leaveOpenOnKeyguardHide() ||
                        statusbarState == StatusBarState.SHADE_LOCKED)) {
            // Usually listening to the isShown is enough to determine this, but there is some
            // non-trivial reattaching logic happening that will make the view not-shown earlier
            return true
        }
        return mediaCarousel.isShown || animator.isRunning
    }

    private fun adjustAnimatorForTransition(desiredLocation: Int, previousLocation: Int) {
        val (animDuration, delay) = getAnimationParams(previousLocation, desiredLocation)
        animator.apply {
            duration = animDuration
            startDelay = delay
        }
    }

    private fun getAnimationParams(previousLocation: Int, desiredLocation: Int): Pair<Long, Long> {
        var animDuration = 200L
        var delay = 0L
        if (previousLocation == LOCATION_LOCKSCREEN && desiredLocation == LOCATION_QQS) {
            // Going to the full shade, let's adjust the animation duration
            if (statusbarState == StatusBarState.SHADE &&
                    keyguardStateController.isKeyguardFadingAway) {
                delay = keyguardStateController.keyguardFadingAwayDelay
            }
            animDuration = StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE.toLong()
        } else if (previousLocation == LOCATION_QQS && desiredLocation == LOCATION_LOCKSCREEN) {
            animDuration = StackStateAnimator.ANIMATION_DURATION_APPEAR_DISAPPEAR.toLong()
        }
        return animDuration to delay
    }

    private fun applyTargetStateIfNotAnimating() {
        if (!animator.isRunning) {
            // Let's immediately apply the target state (which is interpolated) if there is
            // no animation running. Otherwise the animation update will already update
            // the location
            applyState(targetState!!)
        }
    }

    /**
     * Updates the state that the view wants to be in at the end of the animation.
     */
    private fun updateTargetState() {
        if (isCurrentlyInGuidedTransformation()) {
            val progress = getTransformationProgress()
            val currentHost = getHost(desiredLocation)!!
            val previousHost = getHost(previousLocation)!!
            val newState = currentHost.currentState
            val previousState = previousHost.currentState
            targetState = previousState.interpolate(newState, progress)
        } else {
            targetState = getHost(desiredLocation)?.currentState
        }
    }

    /**
     * @return true if this transformation is guided by an external progress like a finger
     */
    private fun isCurrentlyInGuidedTransformation(): Boolean {
        return getTransformationProgress() >= 0
    }

    /**
     * @return the current transformation progress if we're in a guided transformation and -1
     * otherwise
     */
    private fun getTransformationProgress(): Float {
        val progress = getQSTransformationProgress()
        if (progress >= 0) {
            return progress
        }
        return -1.0f
    }

    private fun getQSTransformationProgress(): Float {
        val currentHost = getHost(desiredLocation)
        val previousHost = getHost(previousLocation)
        if (currentHost?.location == LOCATION_QS) {
            if (previousHost?.location == LOCATION_QQS) {
                return qsExpansion
            }
        }
        return -1.0f
    }

    private fun getHost(@MediaLocation location: Int): MediaHost? {
        if (location < 0) {
            return null
        }
        return mediaHosts[location]
    }

    private fun cancelAnimationAndApplyDesiredState() {
        animator.cancel()
        getHost(desiredLocation)?.let {
            applyState(it.currentState)
        }
    }

    private fun applyState(state: MediaState) {
        currentState = state.copy()
        mediaViewManager.setCurrentState(currentState)
        updateHostAttachment()
        if (currentAttachmentLocation == IN_OVERLAY) {
            val boundsOnScreen = state.boundsOnScreen
            mediaCarousel.setLeftTopRightBottom(
                    boundsOnScreen.left,
                    boundsOnScreen.top,
                    boundsOnScreen.right,
                    boundsOnScreen.bottom)
        }
    }

    private fun updateHostAttachment() {
        val inOverlay = isTransitionRunning() && rootOverlay != null
        val newLocation = if (inOverlay) IN_OVERLAY else desiredLocation
        if (currentAttachmentLocation != newLocation) {
            currentAttachmentLocation = newLocation

            // Remove the carousel from the old host
            (mediaCarousel.parent as ViewGroup?)?.removeView(mediaCarousel)

            // Add it to the new one
            val targetHost = getHost(desiredLocation)!!.hostView
            if (inOverlay) {
                rootOverlay!!.add(mediaCarousel)
            } else {
                targetHost.addView(mediaCarousel)
                mediaViewManager.onViewReattached()
            }
        }
    }

    private fun isTransitionRunning(): Boolean {
        return isCurrentlyInGuidedTransformation() && getTransformationProgress() != 1.0f ||
                animator.isRunning
    }

    @MediaLocation
    private fun calculateLocation(): Int {
        val onLockscreen = (!bypassController.bypassEnabled &&
                (statusbarState == StatusBarState.KEYGUARD ||
                        statusbarState == StatusBarState.FULLSCREEN_USER_SWITCHER))
        val allowedOnLockscreen = notifLockscreenUserManager.shouldShowLockscreenNotifications()
        return when {
            qsExpansion > 0.0f && !onLockscreen -> LOCATION_QS
            qsExpansion > 0.4f && onLockscreen -> LOCATION_QS
            onLockscreen && allowedOnLockscreen -> LOCATION_LOCKSCREEN
            else -> LOCATION_QQS
        }
    }

    /**
     * The expansion of quick settings
     */
    @IntDef(prefix = ["LOCATION_"], value = [LOCATION_QS, LOCATION_QQS, LOCATION_LOCKSCREEN])
    @Retention(AnnotationRetention.SOURCE)
    annotation class MediaLocation

    companion object {
        /**
         * Attached in expanded quick settings
         */
        const val LOCATION_QS = 0

        /**
         * Attached in the collapsed QS
         */
        const val LOCATION_QQS = 1

        /**
         * Attached on the lock screen
         */
        const val LOCATION_LOCKSCREEN = 2

        /**
         * Attached at the root of the hierarchy in an overlay
         */
        const val IN_OVERLAY = -1000
    }
}

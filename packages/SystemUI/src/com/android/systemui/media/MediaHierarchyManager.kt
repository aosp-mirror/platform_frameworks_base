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
import android.graphics.Rect
import android.util.MathUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroupOverlay
import com.android.systemui.R
import com.android.systemui.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.StackStateAnimator
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.animation.UniqueObjectHostView
import javax.inject.Inject
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager

/**
 * Similarly to isShown but also excludes views that have 0 alpha
 */
val View.isShownNotFaded: Boolean
    get() {
        var current: View = this
        while (true) {
            if (current.visibility != View.VISIBLE) {
                return false
            }
            if (current.alpha == 0.0f) {
                return false
            }
            val parent = current.parent ?: return false // We are not attached to the view root
            if (parent !is View) {
                // we reached the viewroot, hurray
                return true
            }
            current = parent
        }
    }

/**
 * This manager is responsible for placement of the unique media view between the different hosts
 * and animate the positions of the views to achieve seamless transitions.
 */
@SysUISingleton
class MediaHierarchyManager @Inject constructor(
    private val context: Context,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val bypassController: KeyguardBypassController,
    private val mediaCarouselController: MediaCarouselController,
    private val notifLockscreenUserManager: NotificationLockscreenUserManager,
    configurationController: ConfigurationController,
    wakefulnessLifecycle: WakefulnessLifecycle,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager
) {
    /**
     * The root overlay of the hierarchy. This is where the media notification is attached to
     * whenever the view is transitioning from one host to another. It also make sure that the
     * view is always in its final state when it is attached to a view host.
     */
    private var rootOverlay: ViewGroupOverlay? = null

    private var rootView: View? = null
    private var currentBounds = Rect()
    private var animationStartBounds: Rect = Rect()
    private var targetBounds: Rect = Rect()
    private val mediaFrame
        get() = mediaCarouselController.mediaFrame
    private var statusbarState: Int = statusBarStateController.state
    private var animator = ValueAnimator.ofFloat(0.0f, 1.0f).apply {
        interpolator = Interpolators.FAST_OUT_SLOW_IN
        addUpdateListener {
            updateTargetState()
            interpolateBounds(animationStartBounds, targetBounds, animatedFraction,
                    result = currentBounds)
            applyState(currentBounds)
        }
        addListener(object : AnimatorListenerAdapter() {
            private var cancelled: Boolean = false

            override fun onAnimationCancel(animation: Animator?) {
                cancelled = true
                animationPending = false
                rootView?.removeCallbacks(startAnimation)
            }

            override fun onAnimationEnd(animation: Animator?) {
                if (!cancelled) {
                    applyTargetStateIfNotAnimating()
                }
            }

            override fun onAnimationStart(animation: Animator?) {
                cancelled = false
                animationPending = false
            }
        })
    }

    private val mediaHosts = arrayOfNulls<MediaHost>(LOCATION_LOCKSCREEN + 1)
    /**
     * The last location where this view was at before going to the desired location. This is
     * useful for guided transitions.
     */
    @MediaLocation
    private var previousLocation = -1
    /**
     * The desired location where the view will be at the end of the transition.
     */
    @MediaLocation
    private var desiredLocation = -1

    /**
     * The current attachment location where the view is currently attached.
     * Usually this matches the desired location except for animations whenever a view moves
     * to the new desired location, during which it is in [IN_OVERLAY].
     */
    @MediaLocation
    private var currentAttachmentLocation = -1

    /**
     * Are we currently waiting on an animation to start?
     */
    private var animationPending: Boolean = false
    private val startAnimation: Runnable = Runnable { animator.start() }

    /**
     * The expansion of quick settings
     */
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

    /**
     * Is quick setting expanded?
     */
    var qsExpanded: Boolean = false
        set(value) {
            if (field != value) {
                field = value
            }
            // qs is expanded on LS shade and HS shade
            if (value && (isLockScreenShadeVisibleToUser() || isHomeScreenShadeVisibleToUser())) {
                mediaCarouselController.logSmartspaceImpression()
            }
            // Release shade and back to lock screen
            if (isLockScreenVisibleToUser()) {
                mediaCarouselController.logSmartspaceImpression()
            }
            mediaCarouselController.mediaCarouselScrollHandler.visibleToUser = isVisibleToUser()
        }

    /**
     * distance that the full shade transition takes in order for media to fully transition to the
     * shade
     */
    private var distanceForFullShadeTransition = 0

    /**
     * Delay after which the media will start transitioning to the full shade on the lockscreen.
     */
    private var fullShadeTransitionDelay = 0

    /**
     * The amount of progress we are currently in if we're transitioning to the full shade.
     * 0.0f means we're not transitioning yet, while 1 means we're all the way in the full
     * shade.
     */
    private var fullShadeTransitionProgress = 0f
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (bypassController.bypassEnabled) {
                return
            }
            updateDesiredLocation()
            if (value >= 0) {
                updateTargetState()
                applyTargetStateIfNotAnimating()
            }
        }

    private val isTransitioningToFullShade: Boolean
        get() = fullShadeTransitionProgress != 0f && !bypassController.bypassEnabled

    /**
     * Set the amount of pixels we have currently dragged down if we're transitioning to the full
     * shade. 0.0f means we're not transitioning yet.
     */
    fun setTransitionToFullShadeAmount(value: Float) {
        // If we're transitioning starting on the shade_locked, we don't want any delay and rather
        // have it aligned with the rest of the animation
        val delay = if (statusbarState == StatusBarState.KEYGUARD) {
            fullShadeTransitionDelay
        } else {
            0
        }
        val progress = MathUtils.saturate((value - delay) /
                (distanceForFullShadeTransition - delay))
        fullShadeTransitionProgress = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(progress)
    }

    /**
     * Is the shade currently collapsing from the expanded qs? If we're on the lockscreen and in qs,
     * we wouldn't want to transition in that case.
     */
    var collapsingShadeFromQS: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateDesiredLocation(forceNoAnimation = true)
            }
        }

    /**
     * Are location changes currently blocked?
     */
    private val blockLocationChanges: Boolean
        get() {
            return goingToSleep || dozeAnimationRunning
        }

    /**
     * Are we currently going to sleep
     */
    private var goingToSleep: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    updateDesiredLocation()
                }
            }
        }

    /**
     * Are we currently fullyAwake
     */
    private var fullyAwake: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    updateDesiredLocation(forceNoAnimation = true)
                }
            }
        }

    /**
     * Is the doze animation currently Running
     */
    private var dozeAnimationRunning: Boolean = false
        private set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    updateDesiredLocation()
                }
            }
        }

    init {
        updateConfiguration()
        configurationController.addCallback(object : ConfigurationController.ConfigurationListener {
            override fun onDensityOrFontScaleChanged() {
                updateConfiguration()
            }
        })
        statusBarStateController.addCallback(object : StatusBarStateController.StateListener {
            override fun onStatePreChange(oldState: Int, newState: Int) {
                // We're updating the location before the state change happens, since we want the
                // location of the previous state to still be up to date when the animation starts
                statusbarState = newState
                updateDesiredLocation()
            }

            override fun onStateChanged(newState: Int) {
                updateTargetState()
                // Enters shade from lock screen
                if (newState == StatusBarState.SHADE_LOCKED && isLockScreenShadeVisibleToUser()) {
                    mediaCarouselController.logSmartspaceImpression()
                }
                mediaCarouselController.mediaCarouselScrollHandler.visibleToUser = isVisibleToUser()
            }

            override fun onDozeAmountChanged(linear: Float, eased: Float) {
                dozeAnimationRunning = linear != 0.0f && linear != 1.0f
            }

            override fun onDozingChanged(isDozing: Boolean) {
                if (!isDozing) {
                    dozeAnimationRunning = false
                    // Enters lock screen from screen off
                    if (isLockScreenVisibleToUser()) {
                        mediaCarouselController.logSmartspaceImpression()
                    }
                } else {
                    updateDesiredLocation()
                    qsExpanded = false
                    closeGuts()
                }
                mediaCarouselController.mediaCarouselScrollHandler.visibleToUser = isVisibleToUser()
            }

            override fun onExpandedChanged(isExpanded: Boolean) {
                // Enters shade from home screen
                if (isHomeScreenShadeVisibleToUser()) {
                    mediaCarouselController.logSmartspaceImpression()
                }
                // Back to lock screen from bouncer
                if (isLockScreenVisibleToUser()) {
                    mediaCarouselController.logSmartspaceImpression()
                }
                mediaCarouselController.mediaCarouselScrollHandler.visibleToUser = isVisibleToUser()
            }
        })

        wakefulnessLifecycle.addObserver(object : WakefulnessLifecycle.Observer {
            override fun onFinishedGoingToSleep() {
                goingToSleep = false
            }

            override fun onStartedGoingToSleep() {
                goingToSleep = true
                fullyAwake = false
            }

            override fun onFinishedWakingUp() {
                goingToSleep = false
                fullyAwake = true
            }

            override fun onStartedWakingUp() {
                goingToSleep = false
            }
        })
    }

    private fun updateConfiguration() {
        distanceForFullShadeTransition = context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_qs_transition_distance)
        fullShadeTransitionDelay = context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_media_transition_start_delay)
    }

    /**
     * Register a media host and create a view can be attached to a view hierarchy
     * and where the players will be placed in when the host is the currently desired state.
     *
     * @return the hostView associated with this location
     */
    fun register(mediaObject: MediaHost): UniqueObjectHostView {
        val viewHost = createUniqueObjectHost()
        mediaObject.hostView = viewHost
        mediaObject.addVisibilityChangeListener {
            // Never animate because of a visibility change, only state changes should do that
            updateDesiredLocation(forceNoAnimation = true)
        }
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

    /**
     * Close the guts in all players in [MediaCarouselController].
     */
    fun closeGuts() {
        mediaCarouselController.closeGuts()
    }

    private fun createUniqueObjectHost(): UniqueObjectHostView {
        val viewHost = UniqueObjectHostView(context)
        viewHost.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(p0: View?) {
                if (rootOverlay == null) {
                    rootView = viewHost.viewRootImpl.view
                    rootOverlay = (rootView!!.overlay as ViewGroupOverlay)
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
     *
     * @param forceNoAnimation optional parameter telling the system not to animate
     */
    private fun updateDesiredLocation(forceNoAnimation: Boolean = false) {
        val desiredLocation = calculateLocation()
        if (desiredLocation != this.desiredLocation) {
            if (this.desiredLocation >= 0) {
                previousLocation = this.desiredLocation
            }
            val isNewView = this.desiredLocation == -1
            this.desiredLocation = desiredLocation
            // Let's perform a transition
            val animate = !forceNoAnimation &&
                    shouldAnimateTransition(desiredLocation, previousLocation)
            val (animDuration, delay) = getAnimationParams(previousLocation, desiredLocation)
            val host = getHost(desiredLocation)
            mediaCarouselController.onDesiredLocationChanged(desiredLocation, host, animate,
                    animDuration, delay)
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
            if (currentAttachmentLocation != previousLocation ||
                    !previousHost.hostView.isAttachedToWindow) {
                // Let's animate to the new position, starting from the current position
                // We also go in here in case the view was detached, since the bounds wouldn't
                // be correct anymore
                animationStartBounds.set(currentBounds)
            } else {
                // otherwise, let's take the freshest state, since the current one could
                // be outdated
                animationStartBounds.set(previousHost.currentBounds)
            }
            adjustAnimatorForTransition(desiredLocation, previousLocation)
            if (!animationPending) {
                rootView?.let {
                    // Let's delay the animation start until we finished laying out
                    animationPending = true
                    it.postOnAnimation(startAnimation)
                }
            }
        } else {
            cancelAnimationAndApplyDesiredState()
        }
    }

    private fun shouldAnimateTransition(
        @MediaLocation currentLocation: Int,
        @MediaLocation previousLocation: Int
    ): Boolean {
        if (isCurrentlyInGuidedTransformation()) {
            return false
        }
        // This is an invalid transition, and can happen when using the camera gesture from the
        // lock screen. Disallow.
        if (previousLocation == LOCATION_LOCKSCREEN &&
            desiredLocation == LOCATION_QQS &&
            statusbarState == StatusBarState.SHADE) {
            return false
        }

        if (currentLocation == LOCATION_QQS &&
                previousLocation == LOCATION_LOCKSCREEN &&
                (statusBarStateController.leaveOpenOnKeyguardHide() ||
                        statusbarState == StatusBarState.SHADE_LOCKED)) {
            // Usually listening to the isShown is enough to determine this, but there is some
            // non-trivial reattaching logic happening that will make the view not-shown earlier
            return true
        }
        return mediaFrame.isShownNotFaded || animator.isRunning || animationPending
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
            applyState(targetBounds)
        }
    }

    /**
     * Updates the bounds that the view wants to be in at the end of the animation.
     */
    private fun updateTargetState() {
        if (isCurrentlyInGuidedTransformation()) {
            val progress = getTransformationProgress()
            var endHost = getHost(desiredLocation)!!
            var starthost = getHost(previousLocation)!!
            // If either of the hosts are invisible, let's keep them at the other host location to
            // have a nicer disappear animation. Otherwise the currentBounds of the state might
            // be undefined
            if (!endHost.visible) {
                endHost = starthost
            } else if (!starthost.visible) {
                starthost = endHost
            }
            val newBounds = endHost.currentBounds
            val previousBounds = starthost.currentBounds
            targetBounds = interpolateBounds(previousBounds, newBounds, progress)
        } else {
            val bounds = getHost(desiredLocation)?.currentBounds ?: return
            targetBounds.set(bounds)
        }
    }

    private fun interpolateBounds(
        startBounds: Rect,
        endBounds: Rect,
        progress: Float,
        result: Rect? = null
    ): Rect {
        val left = MathUtils.lerp(startBounds.left.toFloat(),
                endBounds.left.toFloat(), progress).toInt()
        val top = MathUtils.lerp(startBounds.top.toFloat(),
                endBounds.top.toFloat(), progress).toInt()
        val right = MathUtils.lerp(startBounds.right.toFloat(),
                endBounds.right.toFloat(), progress).toInt()
        val bottom = MathUtils.lerp(startBounds.bottom.toFloat(),
                endBounds.bottom.toFloat(), progress).toInt()
        val resultBounds = result ?: Rect()
        resultBounds.set(left, top, right, bottom)
        return resultBounds
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
        if (isTransitioningToFullShade) {
            return fullShadeTransitionProgress
        }
        return -1.0f
    }

    private fun getQSTransformationProgress(): Float {
        val currentHost = getHost(desiredLocation)
        val previousHost = getHost(previousLocation)
        if (currentHost?.location == LOCATION_QS) {
            if (previousHost?.location == LOCATION_QQS) {
                if (previousHost.visible || statusbarState != StatusBarState.KEYGUARD) {
                    return qsExpansion
                }
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
            applyState(it.currentBounds, immediately = true)
        }
    }

    /**
     * Apply the current state to the view, updating it's bounds and desired state
     */
    private fun applyState(bounds: Rect, immediately: Boolean = false) {
        currentBounds.set(bounds)
        val currentlyInGuidedTransformation = isCurrentlyInGuidedTransformation()
        val startLocation = if (currentlyInGuidedTransformation) previousLocation else -1
        val progress = if (currentlyInGuidedTransformation) getTransformationProgress() else 1.0f
        val endLocation = desiredLocation
        mediaCarouselController.setCurrentState(startLocation, endLocation, progress, immediately)
        updateHostAttachment()
        if (currentAttachmentLocation == IN_OVERLAY) {
            mediaFrame.setLeftTopRightBottom(
                    currentBounds.left,
                    currentBounds.top,
                    currentBounds.right,
                    currentBounds.bottom)
        }
    }

    private fun updateHostAttachment() {
        val inOverlay = isTransitionRunning() && rootOverlay != null
        val newLocation = if (inOverlay) IN_OVERLAY else desiredLocation
        if (currentAttachmentLocation != newLocation) {
            currentAttachmentLocation = newLocation

            // Remove the carousel from the old host
            (mediaFrame.parent as ViewGroup?)?.removeView(mediaFrame)

            // Add it to the new one
            val targetHost = getHost(desiredLocation)!!.hostView
            if (inOverlay) {
                rootOverlay!!.add(mediaFrame)
            } else {
                // When adding back to the host, let's make sure to reset the bounds.
                // Usually adding the view will trigger a layout that does this automatically,
                // but we sometimes suppress this.
                targetHost.addView(mediaFrame)
                val left = targetHost.paddingLeft
                val top = targetHost.paddingTop
                mediaFrame.setLeftTopRightBottom(
                        left,
                        top,
                        left + currentBounds.width(),
                        top + currentBounds.height())
            }
        }
    }

    private fun isTransitionRunning(): Boolean {
        return isCurrentlyInGuidedTransformation() && getTransformationProgress() != 1.0f ||
                animator.isRunning || animationPending
    }

    @MediaLocation
    private fun calculateLocation(): Int {
        if (blockLocationChanges) {
            // Keep the current location until we're allowed to again
            return desiredLocation
        }
        val onLockscreen = (!bypassController.bypassEnabled &&
                (statusbarState == StatusBarState.KEYGUARD ||
                        statusbarState == StatusBarState.FULLSCREEN_USER_SWITCHER))
        val allowedOnLockscreen = notifLockscreenUserManager.shouldShowLockscreenNotifications()
        val location = when {
            qsExpansion > 0.0f && !onLockscreen -> LOCATION_QS
            qsExpansion > 0.4f && onLockscreen -> LOCATION_QS
            onLockscreen && isTransitioningToFullShade -> LOCATION_QQS
            onLockscreen && allowedOnLockscreen -> LOCATION_LOCKSCREEN
            else -> LOCATION_QQS
        }
        // When we're on lock screen and the player is not active, we should keep it in QS.
        // Otherwise it will try to animate a transition that doesn't make sense.
        if (location == LOCATION_LOCKSCREEN && getHost(location)?.visible != true &&
                !statusBarStateController.isDozing) {
            return LOCATION_QS
        }
        if (location == LOCATION_LOCKSCREEN && desiredLocation == LOCATION_QS &&
                collapsingShadeFromQS) {
            // When collapsing on the lockscreen, we want to remain in QS
            return LOCATION_QS
        }
        if (location != LOCATION_LOCKSCREEN && desiredLocation == LOCATION_LOCKSCREEN &&
                !fullyAwake) {
            // When unlocking from dozing / while waking up, the media shouldn't be transitioning
            // in an animated way. Let's keep it in the lockscreen until we're fully awake and
            // reattach it without an animation
            return LOCATION_LOCKSCREEN
        }
        return location
    }

    /**
     * Returns true when the media card could be visible to the user if existed.
     */
    private fun isVisibleToUser(): Boolean {
        return isLockScreenVisibleToUser() || isLockScreenShadeVisibleToUser() ||
                isHomeScreenShadeVisibleToUser()
    }

    private fun isLockScreenVisibleToUser(): Boolean {
        return !statusBarStateController.isDozing &&
                !statusBarKeyguardViewManager.isBouncerShowing &&
                statusBarStateController.state == StatusBarState.KEYGUARD &&
                notifLockscreenUserManager.shouldShowLockscreenNotifications() &&
                statusBarStateController.isExpanded &&
                !qsExpanded
    }

    private fun isLockScreenShadeVisibleToUser(): Boolean {
        return !statusBarStateController.isDozing &&
                !statusBarKeyguardViewManager.isBouncerShowing &&
                (statusBarStateController.state == StatusBarState.SHADE_LOCKED ||
                        (statusBarStateController.state == StatusBarState.KEYGUARD && qsExpanded))
    }

    private fun isHomeScreenShadeVisibleToUser(): Boolean {
        return !statusBarStateController.isDozing &&
                statusBarStateController.state == StatusBarState.SHADE &&
                statusBarStateController.isExpanded
    }

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

@IntDef(prefix = ["LOCATION_"], value = [MediaHierarchyManager.LOCATION_QS,
    MediaHierarchyManager.LOCATION_QQS, MediaHierarchyManager.LOCATION_LOCKSCREEN])
@Retention(AnnotationRetention.SOURCE)
annotation class MediaLocation
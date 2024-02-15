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

package com.android.systemui.media.controls.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.IntDef
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.util.MathUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroupOverlay
import androidx.annotation.VisibleForTesting
import com.android.app.animation.Interpolators
import com.android.app.tracing.traceSection
import com.android.keyguard.KeyguardViewController
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dreams.DreamOverlayStateController
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.media.controls.pipeline.MediaDataManager
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.media.dream.MediaDreamComplication
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.CrossFadeHelper
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.StackStateAnimator
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.SplitShadeStateController
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val TAG: String = MediaHierarchyManager::class.java.simpleName

/** Similarly to isShown but also excludes views that have 0 alpha */
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
class MediaHierarchyManager
@Inject
constructor(
    private val context: Context,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val bypassController: KeyguardBypassController,
    private val mediaCarouselController: MediaCarouselController,
    private val mediaManager: MediaDataManager,
    private val keyguardViewController: KeyguardViewController,
    private val dreamOverlayStateController: DreamOverlayStateController,
    private val communalInteractor: CommunalInteractor,
    configurationController: ConfigurationController,
    wakefulnessLifecycle: WakefulnessLifecycle,
    shadeInteractor: ShadeInteractor,
    private val secureSettings: SecureSettings,
    @Main private val handler: Handler,
    @Application private val coroutineScope: CoroutineScope,
    private val splitShadeStateController: SplitShadeStateController,
    private val logger: MediaViewLogger,
    private val mediaFlags: MediaFlags,
) {

    /** Track the media player setting status on lock screen. */
    private var allowMediaPlayerOnLockScreen: Boolean = true
    private val lockScreenMediaPlayerUri =
        secureSettings.getUriFor(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN)

    /**
     * Whether we "skip" QQS during panel expansion.
     *
     * This means that when expanding the panel we go directly to QS. Also when we are on QS and
     * start closing the panel, it fully collapses instead of going to QQS.
     */
    private var skipQqsOnExpansion: Boolean = false

    /**
     * The root overlay of the hierarchy. This is where the media notification is attached to
     * whenever the view is transitioning from one host to another. It also make sure that the view
     * is always in its final state when it is attached to a view host.
     */
    private var rootOverlay: ViewGroupOverlay? = null

    private var rootView: View? = null
    private var currentBounds = Rect()
    private var animationStartBounds: Rect = Rect()

    private var animationStartClipping = Rect()
    private var currentClipping = Rect()
    private var targetClipping = Rect()

    /**
     * The cross fade progress at the start of the animation. 0.5f means it's just switching between
     * the start and the end location and the content is fully faded, while 0.75f means that we're
     * halfway faded in again in the target state.
     */
    private var animationStartCrossFadeProgress = 0.0f

    /** The starting alpha of the animation */
    private var animationStartAlpha = 0.0f

    /** The starting location of the cross fade if an animation is running right now. */
    @MediaLocation private var crossFadeAnimationStartLocation = -1

    /** The end location of the cross fade if an animation is running right now. */
    @MediaLocation private var crossFadeAnimationEndLocation = -1
    private var targetBounds: Rect = Rect()
    private val mediaFrame
        get() = mediaCarouselController.mediaFrame
    private var statusbarState: Int = statusBarStateController.state
    private var animator =
        ValueAnimator.ofFloat(0.0f, 1.0f).apply {
            interpolator = Interpolators.FAST_OUT_SLOW_IN
            addUpdateListener {
                updateTargetState()
                val currentAlpha: Float
                var boundsProgress = animatedFraction
                if (isCrossFadeAnimatorRunning) {
                    animationCrossFadeProgress =
                        MathUtils.lerp(animationStartCrossFadeProgress, 1.0f, animatedFraction)
                    // When crossfading, let's keep the bounds at the right location during fading
                    boundsProgress = if (animationCrossFadeProgress < 0.5f) 0.0f else 1.0f
                    currentAlpha = calculateAlphaFromCrossFade(animationCrossFadeProgress)
                } else {
                    // If we're not crossfading, let's interpolate from the start alpha to 1.0f
                    currentAlpha = MathUtils.lerp(animationStartAlpha, 1.0f, animatedFraction)
                }
                interpolateBounds(
                    animationStartBounds,
                    targetBounds,
                    boundsProgress,
                    result = currentBounds
                )
                resolveClipping(currentClipping)
                applyState(currentBounds, currentAlpha, clipBounds = currentClipping)
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    private var cancelled: Boolean = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                        animationPending = false
                        rootView?.removeCallbacks(startAnimation)
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        isCrossFadeAnimatorRunning = false
                        if (!cancelled) {
                            applyTargetStateIfNotAnimating()
                        }
                    }

                    override fun onAnimationStart(animation: Animator) {
                        cancelled = false
                        animationPending = false
                    }
                }
            )
        }

    private fun resolveClipping(result: Rect) {
        if (animationStartClipping.isEmpty) result.set(targetClipping)
        else if (targetClipping.isEmpty) result.set(animationStartClipping)
        else result.setIntersect(animationStartClipping, targetClipping)
    }

    private val mediaHosts = arrayOfNulls<MediaHost>(LOCATION_COMMUNAL_HUB + 1)

    /**
     * The last location where this view was at before going to the desired location. This is useful
     * for guided transitions.
     */
    @MediaLocation private var previousLocation = -1
    /** The desired location where the view will be at the end of the transition. */
    @MediaLocation private var desiredLocation = -1

    /**
     * The current attachment location where the view is currently attached. Usually this matches
     * the desired location except for animations whenever a view moves to the new desired location,
     * during which it is in [IN_OVERLAY].
     */
    @MediaLocation private var currentAttachmentLocation = -1

    private var inSplitShade = false

    /** Is there any active media or recommendation in the carousel? */
    private var hasActiveMediaOrRecommendation: Boolean = false
        get() = mediaManager.hasActiveMediaOrRecommendation()

    /** Are we currently waiting on an animation to start? */
    private var animationPending: Boolean = false
    private val startAnimation: Runnable = Runnable { animator.start() }

    /** The expansion of quick settings */
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

    /** Is quick setting expanded? */
    var qsExpanded: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                mediaCarouselController.mediaCarouselScrollHandler.qsExpanded = value
            }
            // qs is expanded on LS shade and HS shade
            if (value && (isLockScreenShadeVisibleToUser() || isHomeScreenShadeVisibleToUser())) {
                mediaCarouselController.logSmartspaceImpression(value)
            }
            updateUserVisibility()
        }

    /**
     * distance that the full shade transition takes in order for media to fully transition to the
     * shade
     */
    private var distanceForFullShadeTransition = 0

    /**
     * The amount of progress we are currently in if we're transitioning to the full shade. 0.0f
     * means we're not transitioning yet, while 1 means we're all the way in the full shade.
     */
    private var fullShadeTransitionProgress = 0f
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (bypassController.bypassEnabled || statusbarState != StatusBarState.KEYGUARD) {
                // No need to do all the calculations / updates below if we're not on the lockscreen
                // or if we're bypassing.
                return
            }
            updateDesiredLocation(forceNoAnimation = isCurrentlyFading())
            if (value >= 0) {
                updateTargetState()
                // Setting the alpha directly, as the below call will use it to update the alpha
                carouselAlpha = calculateAlphaFromCrossFade(field)
                applyTargetStateIfNotAnimating()
            }
        }

    /** Is there currently a cross-fade animation running driven by an animator? */
    private var isCrossFadeAnimatorRunning = false

    /**
     * Are we currently transitionioning from the lockscreen to the full shade
     * [StatusBarState.SHADE_LOCKED] or [StatusBarState.SHADE]. Once the user has dragged down and
     * the transition starts, this will no longer return true.
     */
    private val isTransitioningToFullShade: Boolean
        get() =
            fullShadeTransitionProgress != 0f &&
                !bypassController.bypassEnabled &&
                statusbarState == StatusBarState.KEYGUARD

    /**
     * Set the amount of pixels we have currently dragged down if we're transitioning to the full
     * shade. 0.0f means we're not transitioning yet.
     */
    fun setTransitionToFullShadeAmount(value: Float) {
        // If we're transitioning starting on the shade_locked, we don't want any delay and rather
        // have it aligned with the rest of the animation
        val progress = MathUtils.saturate(value / distanceForFullShadeTransition)
        fullShadeTransitionProgress = progress
    }

    /**
     * Returns the amount of translationY of the media container, during the current guided
     * transformation, if running. If there is no guided transformation running, it will return -1.
     */
    fun getGuidedTransformationTranslationY(): Int {
        if (!isCurrentlyInGuidedTransformation()) {
            return -1
        }
        val startHost = getHost(previousLocation)
        if (startHost == null || !startHost.visible) {
            return 0
        }
        return targetBounds.top - startHost.currentBounds.top
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

    /** Are location changes currently blocked? */
    private val blockLocationChanges: Boolean
        get() {
            return goingToSleep || dozeAnimationRunning
        }

    /** Are we currently going to sleep */
    private var goingToSleep: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    updateDesiredLocation()
                }
            }
        }

    /** Are we currently fullyAwake */
    private var fullyAwake: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    updateDesiredLocation(forceNoAnimation = true)
                }
            }
        }

    /** Is the doze animation currently Running */
    private var dozeAnimationRunning: Boolean = false
        private set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    updateDesiredLocation()
                }
            }
        }

    /** Is the dream overlay currently active */
    private var dreamOverlayActive: Boolean = false
        private set(value) {
            if (field != value) {
                field = value
                updateDesiredLocation(forceNoAnimation = true)
            }
        }

    /** Is the dream media complication currently active */
    private var dreamMediaComplicationActive: Boolean = false
        private set(value) {
            if (field != value) {
                field = value
                updateDesiredLocation(forceNoAnimation = true)
            }
        }

    /** Is the communal UI showing */
    private var isCommunalShowing: Boolean = false

    /**
     * The current cross fade progress. 0.5f means it's just switching between the start and the end
     * location and the content is fully faded, while 0.75f means that we're halfway faded in again
     * in the target state. This is only valid while [isCrossFadeAnimatorRunning] is true.
     */
    private var animationCrossFadeProgress = 1.0f

    /** The current carousel Alpha. */
    private var carouselAlpha: Float = 1.0f
        set(value) {
            if (field == value) {
                return
            }
            field = value
            CrossFadeHelper.fadeIn(mediaFrame, value)
        }

    /**
     * Calculate the alpha of the view when given a cross-fade progress.
     *
     * @param crossFadeProgress The current cross fade progress. 0.5f means it's just switching
     *   between the start and the end location and the content is fully faded, while 0.75f means
     *   that we're halfway faded in again in the target state.
     */
    private fun calculateAlphaFromCrossFade(crossFadeProgress: Float): Float {
        if (crossFadeProgress <= 0.5f) {
            return 1.0f - crossFadeProgress / 0.5f
        } else {
            return (crossFadeProgress - 0.5f) / 0.5f
        }
    }

    init {
        updateConfiguration()
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    updateConfiguration()
                    updateDesiredLocation(forceNoAnimation = true, forceStateUpdate = true)
                }
            }
        )
        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onStatePreChange(oldState: Int, newState: Int) {
                    // We're updating the location before the state change happens, since we want
                    // the
                    // location of the previous state to still be up to date when the animation
                    // starts
                    if (
                        newState == StatusBarState.SHADE_LOCKED &&
                            oldState == StatusBarState.KEYGUARD &&
                            fullShadeTransitionProgress < 1.0f
                    ) {
                        // Since the new state is SHADE_LOCKED, we need to set the transition amount
                        // to maximum if the progress is not 1f.
                        setTransitionToFullShadeAmount(distanceForFullShadeTransition.toFloat())
                    }
                    statusbarState = newState
                    updateDesiredLocation()
                }

                override fun onStateChanged(newState: Int) {
                    updateTargetState()
                    // Enters shade from lock screen
                    if (
                        newState == StatusBarState.SHADE_LOCKED && isLockScreenShadeVisibleToUser()
                    ) {
                        mediaCarouselController.logSmartspaceImpression(qsExpanded)
                    }
                    updateUserVisibility()
                }

                override fun onDozeAmountChanged(linear: Float, eased: Float) {
                    dozeAnimationRunning = linear != 0.0f && linear != 1.0f
                }

                override fun onDozingChanged(isDozing: Boolean) {
                    if (!isDozing) {
                        dozeAnimationRunning = false
                        // Enters lock screen from screen off
                        if (isLockScreenVisibleToUser()) {
                            mediaCarouselController.logSmartspaceImpression(qsExpanded)
                        }
                    } else {
                        updateDesiredLocation()
                        qsExpanded = false
                        closeGuts()
                    }
                    updateUserVisibility()
                }

                override fun onExpandedChanged(isExpanded: Boolean) {
                    // Enters shade from home screen
                    if (isHomeScreenShadeVisibleToUser()) {
                        mediaCarouselController.logSmartspaceImpression(qsExpanded)
                    }
                    updateUserVisibility()
                }
            }
        )

        dreamOverlayStateController.addCallback(
            object : DreamOverlayStateController.Callback {
                override fun onComplicationsChanged() {
                    dreamMediaComplicationActive =
                        dreamOverlayStateController.complications.any {
                            it is MediaDreamComplication
                        }
                }

                override fun onStateChanged() {
                    dreamOverlayStateController.isOverlayActive.also { dreamOverlayActive = it }
                }
            }
        )

        wakefulnessLifecycle.addObserver(
            object : WakefulnessLifecycle.Observer {
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
            }
        )

        mediaCarouselController.updateUserVisibility = this::updateUserVisibility
        mediaCarouselController.updateHostVisibility = {
            mediaHosts.forEach { it?.updateViewVisibility() }
        }

        coroutineScope.launch {
            shadeInteractor.isQsBypassingShade.collect { isExpandImmediateEnabled ->
                skipQqsOnExpansion = isExpandImmediateEnabled
                updateDesiredLocation()
            }
        }

        val settingsObserver: ContentObserver =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (uri == lockScreenMediaPlayerUri) {
                        allowMediaPlayerOnLockScreen =
                            secureSettings.getBoolForUser(
                                Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
                                true,
                                UserHandle.USER_CURRENT
                            )
                    }
                }
            }
        secureSettings.registerContentObserverForUser(
            Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
            settingsObserver,
            UserHandle.USER_ALL
        )

        // Listen to the communal UI state.
        coroutineScope.launch {
            communalInteractor.isCommunalShowing.collect { value ->
                isCommunalShowing = value
                updateDesiredLocation(forceNoAnimation = true)
            }
        }
    }

    private fun updateConfiguration() {
        distanceForFullShadeTransition =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_media_transition_distance
            )
        inSplitShade = splitShadeStateController.shouldUseSplitNotificationShade(context.resources)
    }

    /**
     * Register a media host and create a view can be attached to a view hierarchy and where the
     * players will be placed in when the host is the currently desired state.
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

    /** Close the guts in all players in [MediaCarouselController]. */
    fun closeGuts() {
        mediaCarouselController.closeGuts()
    }

    private fun createUniqueObjectHost(): UniqueObjectHostView {
        val viewHost = UniqueObjectHostView(context)
        viewHost.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(p0: View) {
                    if (rootOverlay == null) {
                        rootView = viewHost.viewRootImpl.view
                        rootOverlay = (rootView!!.overlay as ViewGroupOverlay)
                    }
                    viewHost.removeOnAttachStateChangeListener(this)
                }

                override fun onViewDetachedFromWindow(p0: View) {}
            }
        )
        return viewHost
    }

    /**
     * Updates the location that the view should be in. If it changes, an animation may be triggered
     * going from the old desired location to the new one.
     *
     * @param forceNoAnimation optional parameter telling the system not to animate
     * @param forceStateUpdate optional parameter telling the system to update transition state
     *
     * ```
     *                         even if location did not change
     * ```
     */
    private fun updateDesiredLocation(
        forceNoAnimation: Boolean = false,
        forceStateUpdate: Boolean = false
    ) =
        traceSection("MediaHierarchyManager#updateDesiredLocation") {
            val desiredLocation = calculateLocation()
            if (
                desiredLocation != this.desiredLocation || forceStateUpdate && !blockLocationChanges
            ) {
                if (this.desiredLocation >= 0 && desiredLocation != this.desiredLocation) {
                    // Only update previous location when it actually changes
                    previousLocation = this.desiredLocation
                } else if (forceStateUpdate) {
                    val onLockscreen =
                        (!bypassController.bypassEnabled &&
                            (statusbarState == StatusBarState.KEYGUARD))
                    if (
                        desiredLocation == LOCATION_QS &&
                            previousLocation == LOCATION_LOCKSCREEN &&
                            !onLockscreen
                    ) {
                        // If media active state changed and the device is now unlocked, update the
                        // previous location so we animate between the correct hosts
                        previousLocation = LOCATION_QQS
                    }
                }
                val isNewView = this.desiredLocation == -1
                this.desiredLocation = desiredLocation
                // Let's perform a transition
                val animate =
                    !forceNoAnimation && shouldAnimateTransition(desiredLocation, previousLocation)
                val (animDuration, delay) = getAnimationParams(previousLocation, desiredLocation)
                val host = getHost(desiredLocation)
                val willFade = calculateTransformationType() == TRANSFORMATION_TYPE_FADE
                if (!willFade || isCurrentlyInGuidedTransformation() || !animate) {
                    // if we're fading, we want the desired location / measurement only to change
                    // once fully faded. This is happening in the host attachment
                    mediaCarouselController.onDesiredLocationChanged(
                        desiredLocation,
                        host,
                        animate,
                        animDuration,
                        delay
                    )
                }
                performTransitionToNewLocation(isNewView, animate)
            }
        }

    private fun performTransitionToNewLocation(isNewView: Boolean, animate: Boolean) =
        traceSection("MediaHierarchyManager#performTransitionToNewLocation") {
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
                val wasCrossFading = isCrossFadeAnimatorRunning
                val previewsCrossFadeProgress = animationCrossFadeProgress
                animator.cancel()
                if (
                    currentAttachmentLocation != previousLocation ||
                        !previousHost.hostView.isAttachedToWindow
                ) {
                    // Let's animate to the new position, starting from the current position
                    // We also go in here in case the view was detached, since the bounds wouldn't
                    // be correct anymore
                    animationStartBounds.set(currentBounds)
                    animationStartClipping.set(currentClipping)
                } else {
                    // otherwise, let's take the freshest state, since the current one could
                    // be outdated
                    animationStartBounds.set(previousHost.currentBounds)
                    animationStartClipping.set(previousHost.currentClipping)
                }
                val transformationType = calculateTransformationType()
                var needsCrossFade = transformationType == TRANSFORMATION_TYPE_FADE
                var crossFadeStartProgress = 0.0f
                // The alpha is only relevant when not cross fading
                var newCrossFadeStartLocation = previousLocation
                if (wasCrossFading) {
                    if (currentAttachmentLocation == crossFadeAnimationEndLocation) {
                        if (needsCrossFade) {
                            // We were previously crossFading and we've already reached
                            // the end view, Let's start crossfading from the same position there
                            crossFadeStartProgress = 1.0f - previewsCrossFadeProgress
                        }
                        // Otherwise let's fade in from the current alpha, but not cross fade
                    } else {
                        // We haven't reached the previous location yet, let's still cross fade from
                        // where we were.
                        newCrossFadeStartLocation = crossFadeAnimationStartLocation
                        if (newCrossFadeStartLocation == desiredLocation) {
                            // we're crossFading back to where we were, let's start at the end
                            // position
                            crossFadeStartProgress = 1.0f - previewsCrossFadeProgress
                        } else {
                            // Let's start from where we are right now
                            crossFadeStartProgress = previewsCrossFadeProgress
                            // We need to force cross fading as we haven't reached the end location
                            // yet
                            needsCrossFade = true
                        }
                    }
                } else if (needsCrossFade) {
                    // let's not flicker and start with the same alpha
                    crossFadeStartProgress = (1.0f - carouselAlpha) / 2.0f
                }
                isCrossFadeAnimatorRunning = needsCrossFade
                crossFadeAnimationStartLocation = newCrossFadeStartLocation
                crossFadeAnimationEndLocation = desiredLocation
                animationStartAlpha = carouselAlpha
                animationStartCrossFadeProgress = crossFadeStartProgress
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
        if (skipQqsOnExpansion) {
            return false
        }
        // This is an invalid transition, and can happen when using the camera gesture from the
        // lock screen. Disallow.
        if (
            previousLocation == LOCATION_LOCKSCREEN &&
                desiredLocation == LOCATION_QQS &&
                statusbarState == StatusBarState.SHADE
        ) {
            return false
        }

        if (
            currentLocation == LOCATION_QQS &&
                previousLocation == LOCATION_LOCKSCREEN &&
                (statusBarStateController.leaveOpenOnKeyguardHide() ||
                    statusbarState == StatusBarState.SHADE_LOCKED)
        ) {
            // Usually listening to the isShown is enough to determine this, but there is some
            // non-trivial reattaching logic happening that will make the view not-shown earlier
            return true
        }

        if (
            desiredLocation == LOCATION_QS &&
                previousLocation == LOCATION_LOCKSCREEN &&
                statusbarState == StatusBarState.SHADE
        ) {
            // This is an invalid transition, can happen when tapping on home control and the UMO
            // while being on landscape orientation in tablet.
            return false
        }

        if (
            statusbarState == StatusBarState.KEYGUARD &&
                (currentLocation == LOCATION_LOCKSCREEN || previousLocation == LOCATION_LOCKSCREEN)
        ) {
            // We're always fading from lockscreen to keyguard in situations where the player
            // is already fully hidden
            return false
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
            if (
                statusbarState == StatusBarState.SHADE &&
                    keyguardStateController.isKeyguardFadingAway
            ) {
                delay = keyguardStateController.keyguardFadingAwayDelay
            }
            animDuration = (StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE / 2f).toLong()
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
            applyState(targetBounds, carouselAlpha, clipBounds = targetClipping)
        }
    }

    /** Updates the bounds that the view wants to be in at the end of the animation. */
    private fun updateTargetState() {
        var starthost = getHost(previousLocation)
        var endHost = getHost(desiredLocation)
        if (
            isCurrentlyInGuidedTransformation() &&
                !isCurrentlyFading() &&
                starthost != null &&
                endHost != null
        ) {
            val progress = getTransformationProgress()
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
            targetClipping = endHost.currentClipping
        } else if (endHost != null) {
            val bounds = endHost.currentBounds
            targetBounds.set(bounds)
            targetClipping = endHost.currentClipping
        }
    }

    private fun interpolateBounds(
        startBounds: Rect,
        endBounds: Rect,
        progress: Float,
        result: Rect? = null
    ): Rect {
        val left =
            MathUtils.lerp(startBounds.left.toFloat(), endBounds.left.toFloat(), progress).toInt()
        val top =
            MathUtils.lerp(startBounds.top.toFloat(), endBounds.top.toFloat(), progress).toInt()
        val right =
            MathUtils.lerp(startBounds.right.toFloat(), endBounds.right.toFloat(), progress).toInt()
        val bottom =
            MathUtils.lerp(startBounds.bottom.toFloat(), endBounds.bottom.toFloat(), progress)
                .toInt()
        val resultBounds = result ?: Rect()
        resultBounds.set(left, top, right, bottom)
        return resultBounds
    }

    /** @return true if this transformation is guided by an external progress like a finger */
    fun isCurrentlyInGuidedTransformation(): Boolean {
        return hasValidStartAndEndLocations() &&
            getTransformationProgress() >= 0 &&
            (areGuidedTransitionHostsVisible() || !hasActiveMediaOrRecommendation)
    }

    private fun hasValidStartAndEndLocations(): Boolean {
        return previousLocation != -1 && desiredLocation != -1
    }

    /** Calculate the transformation type for the current animation */
    @VisibleForTesting
    @TransformationType
    fun calculateTransformationType(): Int {
        if (isTransitioningToFullShade) {
            if (inSplitShade && areGuidedTransitionHostsVisible()) {
                return TRANSFORMATION_TYPE_TRANSITION
            }
            return TRANSFORMATION_TYPE_FADE
        }
        if (
            previousLocation == LOCATION_LOCKSCREEN && desiredLocation == LOCATION_QS ||
                previousLocation == LOCATION_QS && desiredLocation == LOCATION_LOCKSCREEN
        ) {
            // animating between ls and qs should fade, as QS is clipped.
            return TRANSFORMATION_TYPE_FADE
        }
        if (previousLocation == LOCATION_LOCKSCREEN && desiredLocation == LOCATION_QQS) {
            // animating between ls and qqs should fade when dragging down via e.g. expand button
            return TRANSFORMATION_TYPE_FADE
        }
        return TRANSFORMATION_TYPE_TRANSITION
    }

    private fun areGuidedTransitionHostsVisible(): Boolean {
        return getHost(previousLocation)?.visible == true &&
            getHost(desiredLocation)?.visible == true
    }

    /**
     * @return the current transformation progress if we're in a guided transformation and -1
     *   otherwise
     */
    private fun getTransformationProgress(): Float {
        if (skipQqsOnExpansion) {
            return -1.0f
        }
        val progress = getQSTransformationProgress()
        if (statusbarState != StatusBarState.KEYGUARD && progress >= 0) {
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
        if (currentHost?.location == LOCATION_QS && !inSplitShade) {
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
            applyState(it.currentBounds, alpha = 1.0f, immediately = true)
        }
    }

    /** Apply the current state to the view, updating it's bounds and desired state */
    private fun applyState(
        bounds: Rect,
        alpha: Float,
        immediately: Boolean = false,
        clipBounds: Rect = EMPTY_RECT
    ) =
        traceSection("MediaHierarchyManager#applyState") {
            currentBounds.set(bounds)
            currentClipping = clipBounds
            carouselAlpha = if (isCurrentlyFading()) alpha else 1.0f
            val onlyUseEndState = !isCurrentlyInGuidedTransformation() || isCurrentlyFading()
            val startLocation = if (onlyUseEndState) -1 else previousLocation
            val progress = if (onlyUseEndState) 1.0f else getTransformationProgress()
            val endLocation = resolveLocationForFading()
            mediaCarouselController.setCurrentState(
                startLocation,
                endLocation,
                progress,
                immediately
            )
            updateHostAttachment()
            if (currentAttachmentLocation == IN_OVERLAY) {
                // Setting the clipping on the hierarchy of `mediaFrame` does not work
                if (!currentClipping.isEmpty) {
                    currentBounds.intersect(currentClipping)
                }
                mediaFrame.setLeftTopRightBottom(
                    currentBounds.left,
                    currentBounds.top,
                    currentBounds.right,
                    currentBounds.bottom
                )
            }
        }

    private fun updateHostAttachment() =
        traceSection("MediaHierarchyManager#updateHostAttachment") {
            if (mediaFlags.isSceneContainerEnabled()) {
                // No need to manage transition states - just update the desired location directly
                logger.logMediaHostAttachment(desiredLocation)
                mediaCarouselController.onDesiredLocationChanged(
                    desiredLocation = desiredLocation,
                    desiredHostState = getHost(desiredLocation),
                    animate = false,
                )
                return
            }

            var newLocation = resolveLocationForFading()
            // Don't use the overlay when fading or when we don't have active media
            var canUseOverlay = !isCurrentlyFading() && hasActiveMediaOrRecommendation
            if (isCrossFadeAnimatorRunning) {
                if (
                    getHost(newLocation)?.visible == true &&
                        getHost(newLocation)?.hostView?.isShown == false &&
                        newLocation != desiredLocation
                ) {
                    // We're crossfading but the view is already hidden. Let's move to the overlay
                    // instead. This happens when animating to the full shade using a button click.
                    canUseOverlay = true
                }
            }
            val inOverlay = isTransitionRunning() && rootOverlay != null && canUseOverlay
            newLocation = if (inOverlay) IN_OVERLAY else newLocation
            if (currentAttachmentLocation != newLocation) {
                currentAttachmentLocation = newLocation

                // Remove the carousel from the old host
                (mediaFrame.parent as ViewGroup?)?.removeView(mediaFrame)

                // Add it to the new one
                if (inOverlay) {
                    rootOverlay!!.add(mediaFrame)
                } else {
                    val targetHost = getHost(newLocation)!!.hostView
                    // This will either do a full layout pass and remeasure, or it will bypass
                    // that and directly set the mediaFrame's bounds within the premeasured host.
                    targetHost.addView(mediaFrame)
                }
                logger.logMediaHostAttachment(currentAttachmentLocation)
                if (isCrossFadeAnimatorRunning) {
                    // When cross-fading with an animation, we only notify the media carousel of the
                    // location change, once the view is reattached to the new place and not
                    // immediately
                    // when the desired location changes. This callback will update the measurement
                    // of the carousel, only once we've faded out at the old location and then
                    // reattach
                    // to fade it in at the new location.
                    mediaCarouselController.onDesiredLocationChanged(
                        newLocation,
                        getHost(newLocation),
                        animate = false
                    )
                }
            }
        }

    /**
     * Calculate the location when cross fading between locations. While fading out, the content
     * should remain in the previous location, while after the switch it should be at the desired
     * location.
     */
    private fun resolveLocationForFading(): Int {
        if (isCrossFadeAnimatorRunning) {
            // When animating between two hosts with a fade, let's keep ourselves in the old
            // location for the first half, and then switch over to the end location
            if (animationCrossFadeProgress > 0.5 || previousLocation == -1) {
                return crossFadeAnimationEndLocation
            } else {
                return crossFadeAnimationStartLocation
            }
        }
        return desiredLocation
    }

    private fun isTransitionRunning(): Boolean {
        return isCurrentlyInGuidedTransformation() && getTransformationProgress() != 1.0f ||
            animator.isRunning ||
            animationPending
    }

    @MediaLocation
    private fun calculateLocation(): Int {
        if (blockLocationChanges) {
            // Keep the current location until we're allowed to again
            return desiredLocation
        }
        val onLockscreen =
            (!bypassController.bypassEnabled && (statusbarState == StatusBarState.KEYGUARD))
        val location =
            when {
                mediaFlags.isSceneContainerEnabled() -> desiredLocation
                dreamOverlayActive && dreamMediaComplicationActive -> LOCATION_DREAM_OVERLAY
                (qsExpansion > 0.0f || inSplitShade) && !onLockscreen -> LOCATION_QS
                qsExpansion > 0.4f && onLockscreen -> LOCATION_QS
                onLockscreen && isSplitShadeExpanding() -> LOCATION_QS
                onLockscreen && isTransformingToFullShadeAndInQQS() -> LOCATION_QQS
                // TODO(b/311234666): revisit logic once interactions between the hub and
                //  shade/keyguard state are finalized
                isCommunalShowing -> LOCATION_COMMUNAL_HUB
                onLockscreen && allowMediaPlayerOnLockScreen -> LOCATION_LOCKSCREEN
                else -> LOCATION_QQS
            }
        // When we're on lock screen and the player is not active, we should keep it in QS.
        // Otherwise it will try to animate a transition that doesn't make sense.
        if (
            location == LOCATION_LOCKSCREEN &&
                getHost(location)?.visible != true &&
                !statusBarStateController.isDozing
        ) {
            return LOCATION_QS
        }
        if (
            location == LOCATION_LOCKSCREEN &&
                desiredLocation == LOCATION_QS &&
                collapsingShadeFromQS
        ) {
            // When collapsing on the lockscreen, we want to remain in QS
            return LOCATION_QS
        }
        if (
            location != LOCATION_LOCKSCREEN && desiredLocation == LOCATION_LOCKSCREEN && !fullyAwake
        ) {
            // When unlocking from dozing / while waking up, the media shouldn't be transitioning
            // in an animated way. Let's keep it in the lockscreen until we're fully awake and
            // reattach it without an animation
            return LOCATION_LOCKSCREEN
        }
        if (skipQqsOnExpansion) {
            // When doing an immediate expand or collapse, we want to keep it in QS.
            return LOCATION_QS
        }
        return location
    }

    private fun isSplitShadeExpanding(): Boolean {
        return inSplitShade && isTransitioningToFullShade
    }

    /** Are we currently transforming to the full shade and already in QQS */
    private fun isTransformingToFullShadeAndInQQS(): Boolean {
        if (!isTransitioningToFullShade) {
            return false
        }
        if (inSplitShade) {
            // Split shade doesn't use QQS.
            return false
        }
        return fullShadeTransitionProgress > 0.5f
    }

    /** Is the current transformationType fading */
    private fun isCurrentlyFading(): Boolean {
        if (isSplitShadeExpanding()) {
            // Split shade always uses transition instead of fade.
            return false
        }
        if (isTransitioningToFullShade) {
            return true
        }
        return isCrossFadeAnimatorRunning
    }

    /** Update whether or not the media carousel could be visible to the user */
    private fun updateUserVisibility() {
        val shadeVisible =
            isLockScreenVisibleToUser() ||
                isLockScreenShadeVisibleToUser() ||
                isHomeScreenShadeVisibleToUser()
        val mediaVisible = qsExpanded || hasActiveMediaOrRecommendation
        mediaCarouselController.mediaCarouselScrollHandler.visibleToUser =
            shadeVisible && mediaVisible
    }

    private fun isLockScreenVisibleToUser(): Boolean {
        return !statusBarStateController.isDozing &&
            !keyguardViewController.isBouncerShowing &&
            statusBarStateController.state == StatusBarState.KEYGUARD &&
            allowMediaPlayerOnLockScreen &&
            statusBarStateController.isExpanded &&
            !qsExpanded
    }

    private fun isLockScreenShadeVisibleToUser(): Boolean {
        return !statusBarStateController.isDozing &&
            !keyguardViewController.isBouncerShowing &&
            (statusBarStateController.state == StatusBarState.SHADE_LOCKED ||
                (statusBarStateController.state == StatusBarState.KEYGUARD && qsExpanded))
    }

    private fun isHomeScreenShadeVisibleToUser(): Boolean {
        return !statusBarStateController.isDozing &&
            statusBarStateController.state == StatusBarState.SHADE &&
            statusBarStateController.isExpanded
    }

    companion object {
        /** Attached in expanded quick settings */
        const val LOCATION_QS = 0

        /** Attached in the collapsed QS */
        const val LOCATION_QQS = 1

        /** Attached on the lock screen */
        const val LOCATION_LOCKSCREEN = 2

        /** Attached on the dream overlay */
        const val LOCATION_DREAM_OVERLAY = 3

        /** Attached to a view in the communal UI grid */
        const val LOCATION_COMMUNAL_HUB = 4

        /** Attached at the root of the hierarchy in an overlay */
        const val IN_OVERLAY = -1000

        /**
         * The default transformation type where the hosts transform into each other using a direct
         * transition
         */
        const val TRANSFORMATION_TYPE_TRANSITION = 0

        /**
         * A transformation type where content fades from one place to another instead of
         * transitioning
         */
        const val TRANSFORMATION_TYPE_FADE = 1
    }
}

private val EMPTY_RECT = Rect()

@IntDef(
    prefix = ["TRANSFORMATION_TYPE_"],
    value =
        [
            MediaHierarchyManager.TRANSFORMATION_TYPE_TRANSITION,
            MediaHierarchyManager.TRANSFORMATION_TYPE_FADE
        ]
)
@Retention(AnnotationRetention.SOURCE)
private annotation class TransformationType

@IntDef(
    prefix = ["LOCATION_"],
    value =
        [
            MediaHierarchyManager.LOCATION_QS,
            MediaHierarchyManager.LOCATION_QQS,
            MediaHierarchyManager.LOCATION_LOCKSCREEN,
            MediaHierarchyManager.LOCATION_DREAM_OVERLAY,
            MediaHierarchyManager.LOCATION_COMMUNAL_HUB,
        ]
)
@Retention(AnnotationRetention.SOURCE)
annotation class MediaLocation

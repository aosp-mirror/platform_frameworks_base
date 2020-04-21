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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroupOverlay
import android.widget.LinearLayout

import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.media.InfoMediaManager
import com.android.settingslib.media.LocalMediaManager
import com.android.systemui.Interpolators
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.VisualStabilityManager
import com.android.systemui.statusbar.notification.stack.StackStateAnimator
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.animation.UniqueObjectHost
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This manager is responsible for placement of the unique media view between the different hosts
 * and animate the positions of the views to achieve seamless transitions.
 */
@Singleton
class MediaHierarchyManager @Inject constructor(
    private val context: Context,
    @Main private val foregroundExecutor: Executor,
    @Background private val backgroundExecutor: DelayableExecutor,
    private val localBluetoothManager: LocalBluetoothManager?,
    private val visualStabilityManager: VisualStabilityManager,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val bypassController: KeyguardBypassController,
    private val activityStarter: ActivityStarter,
    mediaManager: MediaDataManager
) {
    private var rootOverlay: ViewGroupOverlay? = null
    private lateinit var currentState: MediaState
    private var animationStartState: MediaState? = null
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
    private val mediaCarousel: ViewGroup
    private val mediaContent: ViewGroup
    private val mediaPlayers: MutableMap<String, MediaControlPanel> = mutableMapOf()
    private val mediaHosts = arrayOfNulls<MediaHost>(LOCATION_LOCKSCREEN + 1)
    private val visualStabilityCallback = ::reorderAllPlayers
    private var previousLocation = -1
    private var desiredLocation = -1
    private var currentAttachmentLocation = -1

    var shouldListen = true
        set(value) {
            field = value
            for (player in mediaPlayers.values) {
                player.setListening(shouldListen)
            }
        }

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
        mediaCarousel = inflateMediaCarousel()
        mediaContent = mediaCarousel.requireViewById(R.id.media_carousel)
        mediaManager.addListener(object : MediaDataManager.Listener {
            override fun onMediaDataLoaded(key: String, data: MediaData) {
                updateView(key, data)
            }

            override fun onMediaDataRemoved(key: String) {
                val removed = mediaPlayers.remove(key)
                removed?.apply {
                    mediaContent.removeView(removed.view)
                }
            }
        })
        statusBarStateController.addCallback(object : StatusBarStateController.StateListener {
            override fun onStateChanged(newState: Int) {
                updateDesiredLocation()
            }
        })
    }

    private fun inflateMediaCarousel(): ViewGroup {
        return LayoutInflater.from(context).inflate(
                R.layout.media_carousel, UniqueObjectHost(context), false) as ViewGroup
    }

    private fun reorderAllPlayers() {
        for (mediaPlayer in mediaPlayers.values) {
            val view = mediaPlayer.view
            if (mediaPlayer.isPlaying && mediaContent.indexOfChild(view) != 0) {
                mediaContent.removeView(view)
                mediaContent.addView(view, 0)
            }
        }
        updateMediaPaddings()
    }

    private fun updateView(key: String, data: MediaData) {
        var existingPlayer = mediaPlayers[key]
        if (existingPlayer == null) {
            // Set up listener for device changes
            // TODO: integrate with MediaTransferManager?
            val imm = InfoMediaManager(context, data.packageName,
                    null /* notification */, localBluetoothManager)
            val routeManager = LocalMediaManager(context, localBluetoothManager,
                    imm, data.packageName)

            existingPlayer = MediaControlPanel(context, mediaContent, routeManager,
                    foregroundExecutor, backgroundExecutor, activityStarter)
            mediaPlayers[key] = existingPlayer
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            existingPlayer.view.setLayoutParams(lp)
            existingPlayer.setListening(shouldListen)
            if (existingPlayer.isPlaying) {
                mediaContent.addView(existingPlayer.view, 0)
            } else {
                mediaContent.addView(existingPlayer.view)
            }
        } else if (existingPlayer.isPlaying &&
                mediaContent.indexOfChild(existingPlayer.view) != 0) {
            if (visualStabilityManager.isReorderingAllowed) {
                mediaContent.removeView(existingPlayer.view)
                mediaContent.addView(existingPlayer.view, 0)
            } else {
                visualStabilityManager.addReorderingAllowedCallback(visualStabilityCallback)
            }
        }
        existingPlayer.bind(data)
        updateMediaPaddings()
    }

    private fun updateMediaPaddings() {
        val padding = context.resources.getDimensionPixelSize(R.dimen.qs_media_padding)
        val childCount = mediaContent.childCount
        for (i in 0 until childCount) {
            val mediaView = mediaContent.getChildAt(i)
            val desiredPaddingEnd = if (i == childCount - 1) 0 else padding
            val layoutParams = mediaView.layoutParams as ViewGroup.MarginLayoutParams
            if (layoutParams.marginEnd != desiredPaddingEnd) {
                layoutParams.marginEnd = desiredPaddingEnd
                mediaView.layoutParams = layoutParams
            }
        }

    }

    /**
     * Register a media host and create a view can be attached to a view hierarchy
     * and where the players will be placed in when the host is the currently desired state.
     *
     * @return the hostView associated with this location
     */
    fun register(mediaObject: MediaHost) : ViewGroup {
        val viewHost = createUniqueObjectHost()
        mediaObject.hostView = viewHost;
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

    private fun createUniqueObjectHost(): UniqueObjectHost {
        val viewHost = UniqueObjectHost(context)
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

    private fun updateDesiredLocation() {
        val desiredLocation = calculateLocation()
        if (desiredLocation != this.desiredLocation) {
            if (this.desiredLocation >= 0) {
                previousLocation = this.desiredLocation
            }
            val isNewView = this.desiredLocation == -1
            this.desiredLocation = desiredLocation
            // Let's perform a transition
            performTransition(applyImmediately = isNewView)
        }
    }

    private fun performTransition(applyImmediately: Boolean) {
        if (previousLocation < 0 || applyImmediately) {
            cancelAnimationAndApplyDesiredState()
            return
        }
        val currentHost = getHost(desiredLocation)
        val previousHost = getHost(previousLocation)
        if (currentHost == null || previousHost == null) {
            cancelAnimationAndApplyDesiredState()
            return;
        }
        updateTargetState()
        if (isCurrentlyInGuidedTransformation()) {
            applyTargetStateIfNotAnimating()
        } else if (shouldAnimateTransition(currentHost, previousHost)) {
            animator.cancel()
            // Let's animate to the new position, starting from the current position
            animationStartState = currentState.copy()
            adjustAnimatorForTransition(previousLocation, desiredLocation)
            animator.start()
        } else {
            cancelAnimationAndApplyDesiredState()
        }
    }

    private fun shouldAnimateTransition(currentHost: MediaHost, previousHost: MediaHost): Boolean {
        if (currentHost.location == LOCATION_QQS
                && previousHost.location == LOCATION_LOCKSCREEN
                && (statusBarStateController.leaveOpenOnKeyguardHide()
                        || statusBarStateController.state == StatusBarState.SHADE_LOCKED)) {
            // Usually listening to the isShown is enough to determine this, but there is some
            // non-trivial reattaching logic happening that will make the view not-shown earlier
            return true
        }
        return mediaCarousel.isShown || animator.isRunning
    }

    private fun adjustAnimatorForTransition(previousLocation: Int, desiredLocation: Int) {
        var animDuration = 200L
        var delay = 0L
        if (previousLocation == LOCATION_LOCKSCREEN && desiredLocation == LOCATION_QQS) {
            // Going to the full shade, let's adjust the animation duration
            if (statusBarStateController.state == StatusBarState.SHADE
                    && keyguardStateController.isKeyguardFadingAway) {
                delay = keyguardStateController.keyguardFadingAwayDelay
            }
            animDuration = StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE.toLong()
        } else if (previousLocation == LOCATION_QQS && desiredLocation == LOCATION_LOCKSCREEN) {
            animDuration = StackStateAnimator.ANIMATION_DURATION_APPEAR_DISAPPEAR.toLong()
        }
        animator.apply {
            duration  = animDuration
            startDelay = delay
        }

    }

    private fun applyTargetStateIfNotAnimating() {
        if (!animator.isRunning) {
            // Let's immediately apply the target state (which is interpolated) if there is
            // no animation running. Otherwise the animation update will already update
            // the location
            applyState(targetState!!)
        }
    }

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
    private fun isCurrentlyInGuidedTransformation() : Boolean {
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
        updateHostAttachment()
        for (mediaPlayer in mediaPlayers.values) {
            val view = mediaPlayer.view
            view.progress = state.expansion
        }
        val boundsOnScreen = state.boundsOnScreen
        if (currentAttachmentLocation == IN_OVERLAY) {
            mediaCarousel.setLeftTopRightBottom(boundsOnScreen.left, boundsOnScreen.top,
                    boundsOnScreen.right, boundsOnScreen.bottom)
        }
        currentState = state.copy()
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
            }
        }
    }

    private fun isTransitionRunning(): Boolean {
        return isCurrentlyInGuidedTransformation() && getTransformationProgress() != 1.0f
                || animator.isRunning
    }

    @MediaLocation
    private fun calculateLocation() : Int {
        val onLockscreen = (!bypassController.bypassEnabled
                && (statusBarStateController.state == StatusBarState.KEYGUARD
                || statusBarStateController.state == StatusBarState.FULLSCREEN_USER_SWITCHER))
        return when {
            qsExpansion > 0.0f && !onLockscreen -> LOCATION_QS
            qsExpansion > 0.4f && onLockscreen -> LOCATION_QS
            onLockscreen -> LOCATION_LOCKSCREEN
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
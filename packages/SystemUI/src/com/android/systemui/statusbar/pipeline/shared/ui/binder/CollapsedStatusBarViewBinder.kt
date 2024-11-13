/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.binder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.statusbar.chips.ui.binder.OngoingActivityChipBinder
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.core.StatusBarSimpleFragment
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.CollapsedStatusBarViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Interface to assist with binding the [CollapsedStatusBarFragment] to
 * [CollapsedStatusBarViewModel]. Used only to enable easy testing of [CollapsedStatusBarFragment].
 */
interface CollapsedStatusBarViewBinder {
    /**
     * Binds the view to the view-model. [listener] will be notified whenever an event that may
     * change the status bar visibility occurs.
     */
    fun bind(
        view: View,
        viewModel: CollapsedStatusBarViewModel,
        listener: StatusBarVisibilityChangeListener,
    )
}

@SysUISingleton
class CollapsedStatusBarViewBinderImpl @Inject constructor() : CollapsedStatusBarViewBinder {
    override fun bind(
        view: View,
        viewModel: CollapsedStatusBarViewModel,
        listener: StatusBarVisibilityChangeListener,
    ) {
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    viewModel.isTransitioningFromLockscreenToOccluded.collect {
                        listener.onStatusBarVisibilityMaybeChanged()
                    }
                }

                launch {
                    viewModel.transitionFromLockscreenToDreamStartedEvent.collect {
                        listener.onTransitionFromLockscreenToDreamStarted()
                    }
                }

                if (NotificationsLiveDataStoreRefactor.isEnabled) {
                    val displayId = view.display.displayId
                    val lightsOutView: View = view.requireViewById(R.id.notification_lights_out)
                    launch {
                        viewModel.areNotificationsLightsOut(displayId).collect { show ->
                            animateLightsOutView(lightsOutView, show)
                        }
                    }
                }

                if (Flags.statusBarScreenSharingChips() && !Flags.statusBarRonChips()) {
                    val primaryChipView: View =
                        view.requireViewById(R.id.ongoing_activity_chip_primary)
                    launch {
                        viewModel.primaryOngoingActivityChip.collect { primaryChipModel ->
                            OngoingActivityChipBinder.bind(primaryChipModel, primaryChipView)
                            if (StatusBarSimpleFragment.isEnabled) {
                                when (primaryChipModel) {
                                    is OngoingActivityChipModel.Shown ->
                                        primaryChipView.show(shouldAnimateChange = true)
                                    is OngoingActivityChipModel.Hidden ->
                                        primaryChipView.hide(
                                            shouldAnimateChange = primaryChipModel.shouldAnimate
                                        )
                                }
                            } else {
                                when (primaryChipModel) {
                                    is OngoingActivityChipModel.Shown ->
                                        listener.onOngoingActivityStatusChanged(
                                            hasPrimaryOngoingActivity = true,
                                            hasSecondaryOngoingActivity = false,
                                            shouldAnimate = true,
                                        )
                                    is OngoingActivityChipModel.Hidden ->
                                        listener.onOngoingActivityStatusChanged(
                                            hasPrimaryOngoingActivity = false,
                                            hasSecondaryOngoingActivity = false,
                                            shouldAnimate = primaryChipModel.shouldAnimate,
                                        )
                                }
                            }
                        }
                    }
                }

                if (Flags.statusBarScreenSharingChips() && Flags.statusBarRonChips()) {
                    val primaryChipView: View =
                        view.requireViewById(R.id.ongoing_activity_chip_primary)
                    val secondaryChipView: View =
                        view.requireViewById(R.id.ongoing_activity_chip_secondary)
                    launch {
                        viewModel.ongoingActivityChips.collect { chips ->
                            OngoingActivityChipBinder.bind(chips.primary, primaryChipView)
                            // TODO(b/364653005): Don't show the secondary chip if there isn't
                            // enough space for it.
                            OngoingActivityChipBinder.bind(chips.secondary, secondaryChipView)

                            if (StatusBarSimpleFragment.isEnabled) {
                                primaryChipView.adjustVisibility(chips.primary.toVisibilityModel())
                                secondaryChipView.adjustVisibility(
                                    chips.secondary.toVisibilityModel()
                                )
                            } else {
                                listener.onOngoingActivityStatusChanged(
                                    hasPrimaryOngoingActivity =
                                        chips.primary is OngoingActivityChipModel.Shown,
                                    hasSecondaryOngoingActivity =
                                        chips.secondary is OngoingActivityChipModel.Shown,
                                    // TODO(b/364653005): Figure out the animation story here.
                                    shouldAnimate = true,
                                )
                            }
                        }
                    }
                }

                if (SceneContainerFlag.isEnabled) {
                    launch {
                        viewModel.isHomeStatusBarAllowedByScene.collect {
                            listener.onIsHomeStatusBarAllowedBySceneChanged(it)
                        }
                    }
                }

                if (StatusBarSimpleFragment.isEnabled) {
                    val clockView = view.requireViewById<View>(R.id.clock)
                    launch { viewModel.isClockVisible.collect { clockView.adjustVisibility(it) } }

                    val notificationIconsArea = view.requireViewById<View>(R.id.notificationIcons)
                    launch {
                        viewModel.isNotificationIconContainerVisible.collect {
                            notificationIconsArea.adjustVisibility(it)
                        }
                    }

                    val systemInfoView =
                        view.requireViewById<View>(R.id.status_bar_end_side_content)
                    // TODO(b/364360986): Also handle operator name view.
                    launch {
                        viewModel.isSystemInfoVisible.collect {
                            systemInfoView.adjustVisibility(it)
                            // TODO(b/364360986): The system info view has a custom alpha controller
                            // in CollapsedStatusBarFragment.
                        }
                    }
                }
            }
        }
    }

    private fun OngoingActivityChipModel.toVisibilityModel():
        CollapsedStatusBarViewModel.VisibilityModel {
        return CollapsedStatusBarViewModel.VisibilityModel(
            visibility = if (this is OngoingActivityChipModel.Shown) View.VISIBLE else View.GONE,
            // TODO(b/364653005): Figure out the animation story here.
            shouldAnimateChange = true,
        )
    }

    private fun animateLightsOutView(view: View, visible: Boolean) {
        view.animate().cancel()

        val alpha = if (visible) 1f else 0f
        val duration = if (visible) 750L else 250L
        val visibility = if (visible) View.VISIBLE else View.GONE

        if (visible) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }

        view
            .animate()
            .alpha(alpha)
            .setDuration(duration)
            .setListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.alpha = alpha
                        view.visibility = visibility
                        // Unset the listener, otherwise this may persist for
                        // another view property animation
                        view.animate().setListener(null)
                    }
                }
            )
            .start()
    }

    private fun View.adjustVisibility(model: CollapsedStatusBarViewModel.VisibilityModel) {
        if (model.visibility == View.VISIBLE) {
            this.show(model.shouldAnimateChange)
        } else {
            this.hide(model.visibility, model.shouldAnimateChange)
        }
    }

    // See CollapsedStatusBarFragment#hide.
    private fun View.hide(state: Int = View.INVISIBLE, shouldAnimateChange: Boolean) {
        val v = this
        v.animate().cancel()
        if (!shouldAnimateChange) {
            v.alpha = 0f
            v.visibility = state
            return
        }

        v.animate()
            .alpha(0f)
            .setDuration(CollapsedStatusBarFragment.FADE_OUT_DURATION.toLong())
            .setStartDelay(0)
            .setInterpolator(Interpolators.ALPHA_OUT)
            .withEndAction { v.visibility = state }
    }

    // See CollapsedStatusBarFragment#show.
    private fun View.show(shouldAnimateChange: Boolean) {
        val v = this
        v.animate().cancel()
        v.visibility = View.VISIBLE
        if (!shouldAnimateChange) {
            v.alpha = 1f
            return
        }
        v.animate()
            .alpha(1f)
            .setDuration(CollapsedStatusBarFragment.FADE_IN_DURATION.toLong())
            .setInterpolator(Interpolators.ALPHA_IN)
            .setStartDelay(CollapsedStatusBarFragment.FADE_IN_DELAY.toLong())
            // We need to clean up any pending end action from animateHide if we call both hide and
            // show in the same frame before the animation actually gets started.
            // cancel() doesn't really remove the end action.
            .withEndAction(null)

        // TODO(b/364360986): Synchronize the motion with the Keyguard fading if necessary.
    }
}

/** Listener for various events that may affect the status bar's visibility. */
interface StatusBarVisibilityChangeListener {
    /**
     * Called when the status bar visibility might have changed due to the device moving to a
     * different state.
     */
    fun onStatusBarVisibilityMaybeChanged()

    /** Called when a transition from lockscreen to dream has started. */
    fun onTransitionFromLockscreenToDreamStarted()

    /**
     * Called when the status of the ongoing activity chip (active or not active) has changed.
     *
     * @param shouldAnimate true if the chip should animate in/out, and false if the chip should
     *   immediately appear/disappear.
     */
    fun onOngoingActivityStatusChanged(
        hasPrimaryOngoingActivity: Boolean,
        hasSecondaryOngoingActivity: Boolean,
        shouldAnimate: Boolean,
    )

    /**
     * Called when the scene state has changed such that the home status bar is newly allowed or no
     * longer allowed. See [CollapsedStatusBarViewModel.isHomeStatusBarAllowedByScene].
     */
    fun onIsHomeStatusBarAllowedBySceneChanged(isHomeStatusBarAllowedByScene: Boolean)
}

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
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.Flags
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.ui.binder.ChipChronometerBinder
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.chips.ui.view.ChipChronometer
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
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

                if (Flags.statusBarScreenSharingChips()) {
                    val chipView: View = view.requireViewById(R.id.ongoing_activity_chip)
                    val chipContext = chipView.context
                    val chipIconView: ImageView =
                        chipView.requireViewById(R.id.ongoing_activity_chip_icon)
                    val chipTimeView: ChipChronometer =
                        chipView.requireViewById(R.id.ongoing_activity_chip_time)
                    val chipTextView: TextView =
                        chipView.requireViewById(R.id.ongoing_activity_chip_text)
                    val chipBackgroundView =
                        chipView.requireViewById<ChipBackgroundContainer>(
                            R.id.ongoing_activity_chip_background
                        )
                    launch {
                        viewModel.ongoingActivityChip.collect { chipModel ->
                            when (chipModel) {
                                is OngoingActivityChipModel.Shown -> {
                                    // Data
                                    IconViewBinder.bindNullable(chipModel.icon, chipIconView)
                                    setChipMainContent(chipModel, chipTextView, chipTimeView)
                                    chipView.setOnClickListener(chipModel.onClickListener)

                                    // Colors
                                    val textColor = chipModel.colors.text(chipContext)
                                    chipIconView.imageTintList = ColorStateList.valueOf(textColor)
                                    chipTimeView.setTextColor(textColor)
                                    chipTextView.setTextColor(textColor)
                                    (chipBackgroundView.background as GradientDrawable).color =
                                        chipModel.colors.background(chipContext)

                                    listener.onOngoingActivityStatusChanged(
                                        hasOngoingActivity = true
                                    )
                                }
                                is OngoingActivityChipModel.Hidden -> {
                                    // The Chronometer should be stopped to prevent leaks -- see
                                    // b/192243808 and [Chronometer.start].
                                    chipTimeView.stop()
                                    listener.onOngoingActivityStatusChanged(
                                        hasOngoingActivity = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setChipMainContent(
        chipModel: OngoingActivityChipModel.Shown,
        chipTextView: TextView,
        chipTimeView: ChipChronometer,
    ) {
        when (chipModel) {
            is OngoingActivityChipModel.Shown.Countdown -> {
                chipTextView.text = chipModel.secondsUntilStarted.toString()
                chipTextView.visibility = View.VISIBLE

                // The Chronometer should be stopped to prevent leaks -- see b/192243808 and
                // [Chronometer.start].
                chipTimeView.stop()
                chipTimeView.visibility = View.GONE
            }
            is OngoingActivityChipModel.Shown.Timer -> {
                ChipChronometerBinder.bind(chipModel.startTimeMs, chipTimeView)
                chipTimeView.visibility = View.VISIBLE

                chipTextView.visibility = View.GONE
            }
        }
        updateChipTextPadding(chipModel, chipTextView, chipTimeView)
    }

    private fun updateChipTextPadding(
        chipModel: OngoingActivityChipModel.Shown,
        chipTextView: TextView,
        chipTimeView: ChipChronometer,
    ) {
        val requiresPadding = chipModel.icon != null
        if (requiresPadding) {
            chipTextView.addChipTextPaddingStart()
            chipTimeView.addChipTextPaddingStart()
        } else {
            chipTextView.removeChipTextPaddingStart()
            chipTimeView.removeChipTextPaddingStart()
        }
    }

    private fun View.addChipTextPaddingStart() {
        this.setPaddingRelative(
            this.context.resources.getDimensionPixelSize(
                R.dimen.ongoing_activity_chip_icon_text_padding
            ),
            paddingTop,
            paddingEnd,
            paddingBottom,
        )
    }

    private fun View.removeChipTextPaddingStart() {
        this.setPaddingRelative(/* start= */ 0, paddingTop, paddingEnd, paddingBottom)
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

    /** Called when the status of the ongoing activity chip (active or not active) has changed. */
    fun onOngoingActivityStatusChanged(hasOngoingActivity: Boolean)
}

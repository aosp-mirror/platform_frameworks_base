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

package com.android.systemui.keyguard.ui.binder

import android.annotation.DrawableRes
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.ViewGroup
import android.view.ViewGroup.OnHierarchyChangeListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.jank.InteractionJankMonitor.CUJ_SCREEN_OFF_SHOW_AOD
import com.android.keyguard.KeyguardClockSwitch.MISSING_CLOCK_ID
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.common.shared.model.TintedIcon
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryHapticsInteractor
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludingAppDeviceEntryMessageViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.ClockController
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.temporarydisplay.ViewPriority
import com.android.systemui.temporarydisplay.chipbar.ChipbarCoordinator
import com.android.systemui.temporarydisplay.chipbar.ChipbarInfo
import javax.inject.Provider
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Bind occludingAppDeviceEntryMessageViewModel to run whenever the keyguard view is attached. */
@ExperimentalCoroutinesApi
object KeyguardRootViewBinder {

    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: KeyguardRootViewModel,
        featureFlags: FeatureFlags,
        occludingAppDeviceEntryMessageViewModel: OccludingAppDeviceEntryMessageViewModel,
        chipbarCoordinator: ChipbarCoordinator,
        keyguardStateController: KeyguardStateController,
        shadeInteractor: ShadeInteractor,
        clockControllerProvider: Provider<ClockController>?,
        interactionJankMonitor: InteractionJankMonitor?,
        deviceEntryHapticsInteractor: DeviceEntryHapticsInteractor?,
        vibratorHelper: VibratorHelper?,
    ): DisposableHandle {
        var onLayoutChangeListener: OnLayoutChange? = null
        val childViews = mutableMapOf<Int, View?>()
        val statusViewId = R.id.keyguard_status_view
        val burnInLayerId = R.id.burn_in_layer
        val aodNotificationIconContainerId = R.id.aod_notification_icon_container
        val largeClockId = R.id.lockscreen_clock_view_large
        val disposableHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch {
                        occludingAppDeviceEntryMessageViewModel.message.collect { biometricMessage
                            ->
                            if (biometricMessage?.message != null) {
                                chipbarCoordinator.displayView(
                                    createChipbarInfo(
                                        biometricMessage.message,
                                        R.drawable.ic_lock,
                                    )
                                )
                            } else {
                                chipbarCoordinator.removeView(ID, "occludingAppMsgNull")
                            }
                        }
                    }

                    if (featureFlags.isEnabled(Flags.MIGRATE_SPLIT_KEYGUARD_BOTTOM_AREA)) {
                        launch { viewModel.alpha.collect { alpha -> view.alpha = alpha } }
                    }

                    if (featureFlags.isEnabled(Flags.MIGRATE_KEYGUARD_STATUS_VIEW)) {
                        launch {
                            viewModel.burnInLayerVisibility.collect { visibility ->
                                childViews[burnInLayerId]?.visibility = visibility
                                // Reset alpha only for the icons, as they currently have their
                                // own animator
                                childViews[aodNotificationIconContainerId]?.alpha = 0f
                            }
                        }

                        launch {
                            viewModel.burnInLayerAlpha.collect { alpha ->
                                childViews[statusViewId]?.alpha = alpha
                                childViews[aodNotificationIconContainerId]?.alpha = alpha
                            }
                        }

                        launch {
                            viewModel.lockscreenStateAlpha.collect { alpha ->
                                childViews[statusViewId]?.alpha = alpha
                            }
                        }

                        launch {
                            viewModel.translationY.collect { y ->
                                childViews[burnInLayerId]?.translationY = y
                            }
                        }

                        launch {
                            viewModel.translationX.collect { x ->
                                childViews[burnInLayerId]?.translationX = x
                            }
                        }

                        launch {
                            viewModel.scale.collect { (scale, scaleClockOnly) ->
                                if (scaleClockOnly) {
                                    childViews[largeClockId]?.let {
                                        it.scaleX = scale
                                        it.scaleY = scale
                                    }
                                } else {
                                    childViews[burnInLayerId]?.scaleX = scale
                                    childViews[burnInLayerId]?.scaleY = scale
                                }
                            }
                        }

                        interactionJankMonitor?.let { jankMonitor ->
                            launch {
                                viewModel.goneToAodTransition.collect {
                                    when (it.transitionState) {
                                        TransitionState.STARTED -> {
                                            val clockId =
                                                clockControllerProvider?.get()?.config?.id
                                                    ?: MISSING_CLOCK_ID
                                            val builder =
                                                InteractionJankMonitor.Configuration.Builder
                                                    .withView(CUJ_SCREEN_OFF_SHOW_AOD, view)
                                                    .setTag(clockId)

                                            jankMonitor.begin(builder)
                                        }
                                        TransitionState.CANCELED ->
                                            jankMonitor.cancel(CUJ_SCREEN_OFF_SHOW_AOD)
                                        TransitionState.FINISHED ->
                                            jankMonitor.end(CUJ_SCREEN_OFF_SHOW_AOD)
                                        TransitionState.RUNNING -> Unit
                                    }
                                }
                            }
                        }
                    }

                    launch {
                        shadeInteractor.isAnyFullyExpanded.collect { isFullyAnyExpanded ->
                            view.visibility =
                                if (isFullyAnyExpanded) {
                                    View.INVISIBLE
                                } else {
                                    View.VISIBLE
                                }
                        }
                    }

                    if (deviceEntryHapticsInteractor != null && vibratorHelper != null) {
                        launch {
                            deviceEntryHapticsInteractor.playSuccessHaptic
                                .filter { it }
                                .collect {
                                    if (
                                        featureFlags.isEnabled(Flags.ONE_WAY_HAPTICS_API_MIGRATION)
                                    ) {
                                        vibratorHelper.performHapticFeedback(
                                            view,
                                            HapticFeedbackConstants.CONFIRM,
                                        )
                                    } else {
                                        vibratorHelper.vibrateAuthSuccess("device-entry::success")
                                    }
                                    deviceEntryHapticsInteractor.handleSuccessHaptic()
                                }
                        }

                        launch {
                            deviceEntryHapticsInteractor.playErrorHaptic
                                .filter { it }
                                .collect {
                                    if (
                                        featureFlags.isEnabled(Flags.ONE_WAY_HAPTICS_API_MIGRATION)
                                    ) {
                                        vibratorHelper.performHapticFeedback(
                                            view,
                                            HapticFeedbackConstants.REJECT,
                                        )
                                    } else {
                                        vibratorHelper.vibrateAuthSuccess("device-entry::error")
                                    }
                                    deviceEntryHapticsInteractor.handleErrorHaptic()
                                }
                        }
                    }
                }
            }
        viewModel.clockControllerProvider = clockControllerProvider

        onLayoutChangeListener = OnLayoutChange(viewModel)
        view.addOnLayoutChangeListener(onLayoutChangeListener)

        // Views will be added or removed after the call to bind(). This is needed to avoid many
        // calls to findViewById
        view.setOnHierarchyChangeListener(
            object : OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View, child: View) {
                    childViews.put(child.id, child)
                }

                override fun onChildViewRemoved(parent: View, child: View) {
                    childViews.remove(child.id)
                }
            }
        )

        return object : DisposableHandle {
            override fun dispose() {
                disposableHandle.dispose()
                view.removeOnLayoutChangeListener(onLayoutChangeListener)
                view.setOnHierarchyChangeListener(null)
                childViews.clear()
            }
        }
    }

    /**
     * Creates an instance of [ChipbarInfo] that can be sent to [ChipbarCoordinator] for display.
     */
    private fun createChipbarInfo(message: String, @DrawableRes icon: Int): ChipbarInfo {
        return ChipbarInfo(
            startIcon =
                TintedIcon(
                    Icon.Resource(icon, null),
                    ChipbarInfo.DEFAULT_ICON_TINT,
                ),
            text = Text.Loaded(message),
            endItem = null,
            vibrationEffect = null,
            windowTitle = "OccludingAppUnlockMsgChip",
            wakeReason = "OCCLUDING_APP_UNLOCK_MSG_CHIP",
            timeoutMs = 3500,
            id = ID,
            priority = ViewPriority.CRITICAL,
            instanceId = null,
        )
    }

    private class OnLayoutChange(private val viewModel: KeyguardRootViewModel) :
        OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int
        ) {
            val nsslPlaceholder = v.findViewById(R.id.nssl_placeholder) as View?

            if (nsslPlaceholder != null) {
                // After layout, ensure the notifications are positioned correctly
                viewModel.onSharedNotificationContainerPositionChanged(
                    nsslPlaceholder.top.toFloat(),
                    nsslPlaceholder.bottom.toFloat(),
                )
            }
        }
    }

    private const val ID = "occluding_app_device_entry_unlock_msg"
}

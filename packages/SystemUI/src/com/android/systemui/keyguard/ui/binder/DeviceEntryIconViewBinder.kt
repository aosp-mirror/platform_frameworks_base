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
 *
 */

package com.android.systemui.keyguard.ui.binder

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.util.StateSet
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.view.isInvisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launch
import com.android.systemui.common.ui.view.LongPressHandlingView
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryBackgroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryForegroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
object DeviceEntryIconViewBinder {
    private const val TAG = "DeviceEntryIconViewBinder"

    /**
     * Updates UI for:
     * - device entry containing view (parent view for the below views)
     *     - long-press handling view (transparent, no UI)
     *     - foreground icon view (lock/unlock/fingerprint)
     *     - background view (optional)
     */
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic
    fun bind(
        applicationScope: CoroutineScope,
        view: DeviceEntryIconView,
        viewModel: DeviceEntryIconViewModel,
        fgViewModel: DeviceEntryForegroundViewModel,
        bgViewModel: DeviceEntryBackgroundViewModel,
        falsingManager: FalsingManager,
        vibratorHelper: VibratorHelper,
    ) {
        DeviceEntryUdfpsRefactor.isUnexpectedlyInLegacyMode()
        val longPressHandlingView = view.longPressHandlingView
        val fgIconView = view.iconView
        val bgView = view.bgView
        longPressHandlingView.listener =
            object : LongPressHandlingView.Listener {
                override fun onLongPressDetected(view: View, x: Int, y: Int) {
                    if (falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)) {
                        return
                    }
                    vibratorHelper.performHapticFeedback(
                        view,
                        HapticFeedbackConstants.CONFIRM,
                    )
                    applicationScope.launch { viewModel.onUserInteraction() }
                }
            }

        view.repeatWhenAttached {
            // Repeat on CREATED so that the view will always observe the entire
            // GONE => AOD transition (even though the view may not be visible until the middle
            // of the transition.
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch("$TAG#viewModel.isVisible") {
                    viewModel.isVisible.collect { isVisible ->
                        longPressHandlingView.isInvisible = !isVisible
                    }
                }
                launch("$TAG#viewModel.isLongPressEnabled") {
                    viewModel.isLongPressEnabled.collect { isEnabled ->
                        longPressHandlingView.setLongPressHandlingEnabled(isEnabled)
                    }
                }
                launch("$TAG#viewModel.isUdfpsSupported") {
                    viewModel.isUdfpsSupported.collect { udfpsSupported ->
                        longPressHandlingView.longPressDuration =
                            if (udfpsSupported) {
                                {
                                    view.resources
                                        .getInteger(R.integer.config_udfpsDeviceEntryIconLongPress)
                                        .toLong()
                                }
                            } else {
                                {
                                    view.resources
                                        .getInteger(R.integer.config_lockIconLongPress)
                                        .toLong()
                                }
                            }
                    }
                }
                launch("$TAG#viewModel.accessibilityDelegateHint") {
                    viewModel.accessibilityDelegateHint.collect { hint ->
                        view.accessibilityHintType = hint
                        if (hint != DeviceEntryIconView.AccessibilityHintType.NONE) {
                            view.setOnClickListener {
                                vibratorHelper.performHapticFeedback(
                                    view,
                                    HapticFeedbackConstants.CONFIRM,
                                )
                                applicationScope.launch { viewModel.onUserInteraction() }
                            }
                        } else {
                            view.setOnClickListener(null)
                        }
                    }
                }
                launch("$TAG#viewModel.useBackgroundProtection") {
                    viewModel.useBackgroundProtection.collect { useBackgroundProtection ->
                        if (useBackgroundProtection) {
                            bgView.visibility = View.VISIBLE
                        } else {
                            bgView.visibility = View.GONE
                        }
                    }
                }
                launch("$TAG#viewModel.burnInOffsets") {
                    viewModel.burnInOffsets.collect { burnInOffsets ->
                        view.translationX = burnInOffsets.x.toFloat()
                        view.translationY = burnInOffsets.y.toFloat()
                        view.aodFpDrawable.progress = burnInOffsets.progress
                    }
                }

                launch("$TAG#viewModel.deviceEntryViewAlpha") {
                    viewModel.deviceEntryViewAlpha.collect { alpha -> view.alpha = alpha }
                }
            }
        }

        fgIconView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Start with an empty state
                fgIconView.setImageState(StateSet.NOTHING, /* merge */ false)
                launch("$TAG#fpIconView.viewModel") {
                    fgViewModel.viewModel.collect { viewModel ->
                        fgIconView.setImageState(
                            view.getIconState(viewModel.type, viewModel.useAodVariant),
                            /* merge */ false
                        )
                        if (viewModel.type.contentDescriptionResId != -1) {
                            fgIconView.contentDescription =
                                fgIconView.resources.getString(
                                    viewModel.type.contentDescriptionResId
                                )
                        }
                        fgIconView.imageTintList = ColorStateList.valueOf(viewModel.tint)
                        fgIconView.setPadding(
                            viewModel.padding,
                            viewModel.padding,
                            viewModel.padding,
                            viewModel.padding,
                        )
                    }
                }
            }
        }

        bgView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch("$TAG#bgViewModel.alpha") {
                    bgViewModel.alpha.collect { alpha -> bgView.alpha = alpha }
                }
                launch("$TAG#bgViewModel.color") {
                    bgViewModel.color.collect { color ->
                        bgView.imageTintList = ColorStateList.valueOf(color)
                    }
                }
            }
        }
    }
}

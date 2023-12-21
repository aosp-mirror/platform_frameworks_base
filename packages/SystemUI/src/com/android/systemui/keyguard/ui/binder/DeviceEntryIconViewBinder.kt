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
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.common.ui.view.LongPressHandlingView
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryBackgroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryForegroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.VibratorHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
object DeviceEntryIconViewBinder {

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
                    applicationScope.launch { viewModel.onLongPress() }
                }
            }

        view.repeatWhenAttached {
            // Repeat on CREATED so that the view will always observe the entire
            // GONE => AOD transition (even though the view may not be visible until the middle
            // of the transition.
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    viewModel.isLongPressEnabled.collect { isEnabled ->
                        longPressHandlingView.setLongPressHandlingEnabled(isEnabled)
                    }
                }
                launch {
                    viewModel.accessibilityDelegateHint.collect { hint ->
                        view.accessibilityHintType = hint
                    }
                }
                launch {
                    viewModel.useBackgroundProtection.collect { useBackgroundProtection ->
                        if (useBackgroundProtection) {
                            bgView.visibility = View.VISIBLE
                        } else {
                            bgView.visibility = View.GONE
                        }
                    }
                }
                launch {
                    viewModel.burnInOffsets.collect { burnInOffsets ->
                        view.translationX = burnInOffsets.x.toFloat()
                        view.translationY = burnInOffsets.y.toFloat()
                        view.aodFpDrawable.progress = burnInOffsets.progress
                    }
                }

                launch { viewModel.deviceEntryViewAlpha.collect { alpha -> view.alpha = alpha } }
            }
        }

        fgIconView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    fgViewModel.viewModel.collect { viewModel ->
                        fgIconView.setImageState(
                            view.getIconState(viewModel.type, viewModel.useAodVariant),
                            /* merge */ false
                        )
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
                launch { bgViewModel.alpha.collect { alpha -> bgView.alpha = alpha } }
                launch {
                    bgViewModel.color.collect { color ->
                        bgView.imageTintList = ColorStateList.valueOf(color)
                    }
                }
            }
        }
    }
}

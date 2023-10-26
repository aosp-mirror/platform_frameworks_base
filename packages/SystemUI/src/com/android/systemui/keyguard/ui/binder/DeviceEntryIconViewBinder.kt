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
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.common.ui.view.LongPressHandlingView
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
object DeviceEntryIconViewBinder {

    /**
     * Updates UI for the device entry icon view (lock, unlock and fingerprint icons) and its
     * background.
     */
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic
    fun bind(
        view: DeviceEntryIconView,
        viewModel: DeviceEntryIconViewModel,
        falsingManager: FalsingManager,
    ) {
        val iconView = view.iconView
        val bgView = view.bgView
        val longPressHandlingView = view.longPressHandlingView
        longPressHandlingView.listener =
            object : LongPressHandlingView.Listener {
                override fun onLongPressDetected(view: View, x: Int, y: Int) {
                    if (falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)) {
                        return
                    }
                    viewModel.onLongPress()
                }
            }
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.iconViewModel.collect { iconViewModel ->
                        iconView.setImageState(
                            view.getIconState(iconViewModel.type, iconViewModel.useAodVariant),
                            /* merge */ false
                        )
                        iconView.imageTintList = ColorStateList.valueOf(iconViewModel.tint)
                        iconView.alpha = iconViewModel.alpha
                        iconView.setPadding(
                            iconViewModel.padding,
                            iconViewModel.padding,
                            iconViewModel.padding,
                            iconViewModel.padding,
                        )
                    }
                }
                launch {
                    viewModel.backgroundViewModel.collect { bgViewModel ->
                        bgView.alpha = bgViewModel.alpha
                        bgView.imageTintList = ColorStateList.valueOf(bgViewModel.tint)
                    }
                }
                launch {
                    viewModel.burnInViewModel.collect { burnInViewModel ->
                        view.translationX = burnInViewModel.x.toFloat()
                        view.translationY = burnInViewModel.y.toFloat()
                        view.aodFpDrawable.progress = burnInViewModel.progress
                    }
                }
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
            }
        }
    }
}

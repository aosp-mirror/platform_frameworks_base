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
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.common.ui.view.LongPressHandlingView
import com.android.systemui.keyguard.ui.viewmodel.KeyguardLongPressViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import kotlinx.coroutines.launch

object KeyguardLongPressViewBinder {
    /**
     * Drives UI for the lock screen long-press feature.
     *
     * @param view The view that listens for long-presses.
     * @param viewModel The view-model that models the UI state.
     * @param onSingleTap A callback to invoke when the system decides that there was a single tap.
     * @param falsingManager [FalsingManager] for making sure the long-press didn't just happen in
     *   the user's pocket.
     * @param settingsMenuView The [View] for the settings menu that shows up when the long-press is
     *   detected. The [view] will be monitored for all touch to know when to actually hide the
     *   settings menu after it had been shown due to an outside touch.
     */
    @JvmStatic
    fun bind(
        view: LongPressHandlingView,
        viewModel: KeyguardLongPressViewModel,
        onSingleTap: () -> Unit,
        falsingManager: FalsingManager,
        settingsMenuView: View,
    ) {
        view.listener =
            object : LongPressHandlingView.Listener {
                override fun onLongPressDetected(view: View, x: Int, y: Int) {
                    if (falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)) {
                        return
                    }

                    viewModel.onLongPress()
                }

                override fun onSingleTapDetected(view: View) {
                    if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                        return
                    }

                    onSingleTap()
                }
            }

        listenForTouchOutsideDismissals(
            outsideView = view,
            settingsMenuView = settingsMenuView,
            onTouchedOutsideSettingsMenu = viewModel::onTouchedOutside,
        )

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isLongPressHandlingEnabled.collect { isEnabled ->
                        view.setLongPressHandlingEnabled(isEnabled)
                    }
                }
            }
        }
    }

    /** Listens for and handles touches outside the settings menu view, to dismiss it. */
    @SuppressLint("ClickableViewAccessibility")
    private fun listenForTouchOutsideDismissals(
        outsideView: View,
        settingsMenuView: View,
        onTouchedOutsideSettingsMenu: () -> Unit,
    ) {
        outsideView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && settingsMenuView.isVisible) {
                val hitRect = Rect()
                settingsMenuView.getHitRect(hitRect)
                if (!hitRect.contains(event.x.toInt(), event.y.toInt())) {
                    onTouchedOutsideSettingsMenu()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
    }
}

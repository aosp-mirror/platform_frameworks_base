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

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.common.ui.view.LongPressHandlingView
import com.android.systemui.keyguard.ui.viewmodel.KeyguardTouchHandlingViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R

object KeyguardLongPressViewBinder {
    /**
     * Drives UI for the lock screen long-press feature.
     *
     * @param view The view that listens for long-presses.
     * @param viewModel The view-model that models the UI state.
     * @param onSingleTap A callback to invoke when the system decides that there was a single tap.
     * @param falsingManager [FalsingManager] for making sure the long-press didn't just happen in
     *   the user's pocket.
     */
    @JvmStatic
    fun bind(
        view: LongPressHandlingView,
        viewModel: KeyguardTouchHandlingViewModel,
        onSingleTap: () -> Unit,
        falsingManager: FalsingManager,
    ) {
        view.accessibilityHintLongPressAction =
            AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
                view.resources.getString(R.string.lock_screen_settings)
            )
        view.listener =
            object : LongPressHandlingView.Listener {
                override fun onLongPressDetected(
                    view: View,
                    x: Int,
                    y: Int,
                    isA11yAction: Boolean
                ) {
                    if (
                        !isA11yAction && falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)
                    ) {
                        return
                    }

                    viewModel.onLongPress(isA11yAction)
                }

                override fun onSingleTapDetected(view: View) {
                    if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                        return
                    }

                    onSingleTap()
                }
            }

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch("$TAG#viewModel.isLongPressHandlingEnabled") {
                    viewModel.isLongPressHandlingEnabled.collect { isEnabled ->
                        view.setLongPressHandlingEnabled(isEnabled)
                        view.contentDescription =
                            if (isEnabled) {
                                view.resources.getString(R.string.accessibility_desc_lock_screen)
                            } else {
                                null
                            }
                    }
                }
            }
        }
    }

    private const val TAG = "KeyguardLongPressViewBinder"
}

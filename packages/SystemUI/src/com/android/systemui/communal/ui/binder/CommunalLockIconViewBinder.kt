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

package com.android.systemui.communal.ui.binder

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.util.Log
import android.util.StateSet
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.view.isInvisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.common.ui.view.TouchHandlingView
import com.android.systemui.communal.ui.viewmodel.CommunalLockIconViewModel
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.kotlin.DisposableHandles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle

object CommunalLockIconViewBinder {
    private const val TAG = "CommunalLockIconViewBinder"

    /**
     * Updates UI for:
     * - device entry containing view (parent view for the below views)
     *     - long-press handling view (transparent, no UI)
     *     - foreground icon view (lock/unlock)
     */
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic
    fun bind(
        applicationScope: CoroutineScope,
        view: DeviceEntryIconView,
        viewModel: CommunalLockIconViewModel,
        falsingManager: FalsingManager,
        vibratorHelper: VibratorHelper,
    ): DisposableHandle {
        val disposables = DisposableHandles()
        val touchHandlingView = view.touchHandlingView
        val fgIconView = view.iconView
        val bgView = view.bgView
        touchHandlingView.listener =
            object : TouchHandlingView.Listener {
                override fun onLongPressDetected(
                    view: View,
                    x: Int,
                    y: Int,
                    isA11yAction: Boolean,
                ) {
                    if (
                        !isA11yAction && falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)
                    ) {
                        Log.d(
                            TAG,
                            "Long press rejected because it is not a11yAction " +
                                "and it is a falseLongTap",
                        )
                        return
                    }
                    vibratorHelper.performHapticFeedback(view, HapticFeedbackConstants.CONFIRM)
                    applicationScope.launch {
                        view.clearFocus()
                        view.clearAccessibilityFocus()
                        viewModel.onUserInteraction()
                    }
                }
            }

        touchHandlingView.isInvisible = false
        view.isClickable = true
        touchHandlingView.longPressDuration = {
            view.resources.getInteger(R.integer.config_lockIconLongPress).toLong()
        }
        bgView.visibility = View.GONE

        disposables +=
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch("$TAG#viewModel.isLongPressEnabled") {
                        viewModel.isLongPressEnabled.collect { isEnabled ->
                            touchHandlingView.setLongPressHandlingEnabled(isEnabled)
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
                                    applicationScope.launch {
                                        view.clearFocus()
                                        view.clearAccessibilityFocus()
                                        viewModel.onUserInteraction()
                                    }
                                }
                            } else {
                                view.setOnClickListener(null)
                            }
                        }
                    }
                }
            }

        disposables +=
            fgIconView.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // Start with an empty state
                    fgIconView.setImageState(StateSet.NOTHING, /* merge */ false)
                    launch("$TAG#fpIconView.viewModel") {
                        viewModel.viewAttributes.collect { attributes ->
                            if (attributes.type.contentDescriptionResId != -1) {
                                fgIconView.contentDescription =
                                    fgIconView.resources.getString(
                                        attributes.type.contentDescriptionResId
                                    )
                            }
                            fgIconView.imageTintList = ColorStateList.valueOf(attributes.tint)
                            fgIconView.setPadding(
                                attributes.padding,
                                attributes.padding,
                                attributes.padding,
                                attributes.padding,
                            )
                            // Set image state at the end after updating other view state. This
                            // method forces the ImageView to recompute the bounds of the drawable.
                            fgIconView.setImageState(
                                view.getIconState(attributes.type, false),
                                /* merge */ false,
                            )
                            // Invalidate, just in case the padding changes just after icon changes
                            fgIconView.invalidate()
                        }
                    }
                }
            }
        return disposables
    }
}

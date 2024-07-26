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

import android.graphics.Rect
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launch
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.common.ui.binder.TextViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSettingsMenuViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardTouchHandlingViewModel
import com.android.systemui.keyguard.util.WallpaperPickerIntentUtils
import com.android.systemui.keyguard.util.WallpaperPickerIntentUtils.LAUNCH_SOURCE_KEYGUARD
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull

object KeyguardSettingsViewBinder {
    fun bind(
        view: View,
        viewModel: KeyguardSettingsMenuViewModel,
        touchHandlingViewModel: KeyguardTouchHandlingViewModel,
        rootViewModel: KeyguardRootViewModel?,
        vibratorHelper: VibratorHelper,
        activityStarter: ActivityStarter
    ): DisposableHandle {
        val disposableHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch("$TAG#viewModel.isVisible") {
                        viewModel.isVisible.distinctUntilChanged().collect { isVisible ->
                            view.animateVisibility(visible = isVisible)
                            if (isVisible) {
                                vibratorHelper.vibrate(KeyguardBottomAreaVibrations.Activated)
                                view.setOnTouchListener(
                                    KeyguardSettingsButtonOnTouchListener(
                                        viewModel = viewModel,
                                    )
                                )
                                IconViewBinder.bind(
                                    icon = viewModel.icon,
                                    view = view.requireViewById(R.id.icon),
                                )
                                TextViewBinder.bind(
                                    view = view.requireViewById(R.id.text),
                                    viewModel = viewModel.text,
                                )
                            }
                        }
                    }

                    // activityStarter will only be null when rendering the preview that
                    // shows up in the Wallpaper Picker app. If we do that, then the
                    // settings menu should never be visible.
                    if (activityStarter != null) {
                        launch("$TAG#viewModel.shouldOpenSettings") {
                            viewModel.shouldOpenSettings
                                .filter { it }
                                .collect {
                                    navigateToLockScreenSettings(
                                        activityStarter = activityStarter,
                                        view = view,
                                    )
                                    viewModel.onSettingsShown()
                                }
                        }
                    }

                    launch("$TAG#rootViewModel?.lastRootViewTapPosition") {
                        rootViewModel?.lastRootViewTapPosition?.filterNotNull()?.collect { point ->
                            if (view.isVisible) {
                                val hitRect = Rect()
                                view.getHitRect(hitRect)
                                if (!hitRect.contains(point.x, point.y)) {
                                    touchHandlingViewModel.onTouchedOutside()
                                }
                            }
                        }
                    }
                }
            }
        return disposableHandle
    }

    /** Opens the wallpaper picker screen after the device is unlocked by the user. */
    private fun navigateToLockScreenSettings(
        activityStarter: ActivityStarter,
        view: View,
    ) {
        activityStarter.postStartActivityDismissingKeyguard(
            WallpaperPickerIntentUtils.getIntent(view.context, LAUNCH_SOURCE_KEYGUARD),
            /* delay= */ 0,
            /* animationController= */ ActivityTransitionAnimator.Controller.fromView(view),
            /* customMessage= */ view.context.getString(R.string.keyguard_unlock_to_customize_ls)
        )
    }

    private fun View.animateVisibility(visible: Boolean) {
        animate()
            .withStartAction {
                if (visible) {
                    alpha = 0f
                    isVisible = true
                }
            }
            .alpha(if (visible) 1f else 0f)
            .withEndAction {
                if (!visible) {
                    isVisible = false
                }
            }
            .start()
    }

    private const val TAG = "KeyguardSettingsViewBinder"
}

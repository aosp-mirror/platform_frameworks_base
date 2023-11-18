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
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.animation.view.LaunchableLinearLayout
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.common.ui.binder.TextViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSettingsMenuViewModel
import com.android.systemui.keyguard.util.WallpaperPickerIntentUtils
import com.android.systemui.keyguard.util.WallpaperPickerIntentUtils.LAUNCH_SOURCE_KEYGUARD
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

object KeyguardSettingsViewBinder {
    fun bind(
        parentView: View,
        viewModel: KeyguardSettingsMenuViewModel,
        vibratorHelper: VibratorHelper,
        activityStarter: ActivityStarter
    ): DisposableHandle {
        val view = parentView.requireViewById<LaunchableLinearLayout>(R.id.keyguard_settings_button)

        val disposableHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        viewModel.isVisible.distinctUntilChanged().collect { isVisible ->
                            view.animateVisibility(visible = isVisible)
                            if (isVisible) {
                                vibratorHelper.vibrate(KeyguardBottomAreaVibrations.Activated)
                                view.setOnTouchListener(
                                    KeyguardSettingsButtonOnTouchListener(
                                        view = view,
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
                        launch {
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
            /* animationController= */ ActivityLaunchAnimator.Controller.fromView(view),
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
}

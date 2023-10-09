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
package com.android.systemui.statusbar.notification.icon.ui.viewbinder

import android.content.res.Resources
import android.view.View
import androidx.annotation.DimenRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.CrossFadeHelper
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerViewModel
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.onDensityOrFontScaleChanged
import com.android.systemui.util.kotlin.stateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Binds a [NotificationIconContainer] to its [view model][NotificationIconContainerViewModel]. */
object NotificationIconContainerViewBinder {
    fun bind(
        view: NotificationIconContainer,
        viewModel: NotificationIconContainerViewModel,
        configurationController: ConfigurationController,
        dozeParameters: DozeParameters,
        featureFlags: FeatureFlagsClassic,
        screenOffAnimationController: ScreenOffAnimationController,
    ): DisposableHandle {
        return view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch { viewModel.animationsEnabled.collect(view::setAnimationsEnabled) }
                launch {
                    viewModel.isDozing.collect { (isDozing, animate) ->
                        val animateIfNotBlanking = animate && !dozeParameters.displayNeedsBlanking
                        view.setDozing(isDozing, animateIfNotBlanking, /* delay= */ 0) {
                            viewModel.completeDozeAnimation()
                        }
                    }
                }
                // TODO(278765923): this should live where AOD is bound, not inside of the NIC
                //  view-binder
                launch {
                    val iconAppearTranslation =
                        view.resources.getConfigAwareDimensionPixelSize(
                            this,
                            configurationController,
                            R.dimen.shelf_appear_translation,
                        )
                    bindVisibility(
                        viewModel,
                        view,
                        featureFlags,
                        screenOffAnimationController,
                        iconAppearTranslation,
                    ) {
                        viewModel.completeVisibilityAnimation()
                    }
                }
            }
        }
    }
    private suspend fun bindVisibility(
        viewModel: NotificationIconContainerViewModel,
        view: NotificationIconContainer,
        featureFlags: FeatureFlagsClassic,
        screenOffAnimationController: ScreenOffAnimationController,
        iconAppearTranslation: StateFlow<Int>,
        onAnimationEnd: () -> Unit,
    ) {
        val statusViewMigrated = featureFlags.isEnabled(Flags.MIGRATE_KEYGUARD_STATUS_VIEW)
        viewModel.isVisible.collect { (isVisible, animate) ->
            view.animate().cancel()
            when {
                !animate -> {
                    view.alpha = 1f
                    if (!statusViewMigrated) {
                        view.translationY = 0f
                    }
                    view.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                }
                featureFlags.isEnabled(Flags.NEW_AOD_TRANSITION) -> {
                    animateInIconTranslation(view, statusViewMigrated)
                    if (isVisible) {
                        CrossFadeHelper.fadeIn(view, onAnimationEnd)
                    } else {
                        CrossFadeHelper.fadeOut(view, onAnimationEnd)
                    }
                }
                !isVisible -> {
                    // Let's make sure the icon are translated to 0, since we cancelled it above
                    animateInIconTranslation(view, statusViewMigrated)
                    CrossFadeHelper.fadeOut(view, onAnimationEnd)
                }
                view.visibility != View.VISIBLE -> {
                    // No fading here, let's just appear the icons instead!
                    view.visibility = View.VISIBLE
                    view.alpha = 1f
                    appearIcons(
                        view,
                        animate = screenOffAnimationController.shouldAnimateAodIcons(),
                        iconAppearTranslation.value,
                        statusViewMigrated,
                    )
                    onAnimationEnd()
                }
                else -> {
                    // Let's make sure the icons are translated to 0, since we cancelled it above
                    animateInIconTranslation(view, statusViewMigrated)
                    // We were fading out, let's fade in instead
                    CrossFadeHelper.fadeIn(view, onAnimationEnd)
                }
            }
        }
    }

    private fun appearIcons(
        view: View,
        animate: Boolean,
        iconAppearTranslation: Int,
        statusViewMigrated: Boolean,
    ) {
        if (animate) {
            if (!statusViewMigrated) {
                view.translationY = -iconAppearTranslation.toFloat()
            }
            view.alpha = 0f
            animateInIconTranslation(view, statusViewMigrated)
            view
                .animate()
                .alpha(1f)
                .setInterpolator(Interpolators.LINEAR)
                .setDuration(AOD_ICONS_APPEAR_DURATION)
                .start()
        } else {
            view.alpha = 1.0f
            if (!statusViewMigrated) {
                view.translationY = 0f
            }
        }
    }

    private fun animateInIconTranslation(view: View, statusViewMigrated: Boolean) {
        if (!statusViewMigrated) {
            view
                .animate()
                .setInterpolator(Interpolators.DECELERATE_QUINT)
                .translationY(0f)
                .setDuration(AOD_ICONS_APPEAR_DURATION)
                .start()
        }
    }

    private const val AOD_ICONS_APPEAR_DURATION: Long = 200
}

fun Resources.getConfigAwareDimensionPixelSize(
    scope: CoroutineScope,
    configurationController: ConfigurationController,
    @DimenRes id: Int,
): StateFlow<Int> =
    scope.stateFlow(
        changedSignals = configurationController.onDensityOrFontScaleChanged,
        getValue = { getDimensionPixelSize(id) }
    )

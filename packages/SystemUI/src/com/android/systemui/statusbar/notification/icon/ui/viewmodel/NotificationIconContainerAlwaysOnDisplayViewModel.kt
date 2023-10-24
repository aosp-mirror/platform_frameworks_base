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
package com.android.systemui.statusbar.notification.icon.ui.viewmodel

import android.graphics.Color
import android.graphics.Rect
import androidx.annotation.ColorInt
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.domain.interactor.NotificationsKeyguardInteractor
import com.android.systemui.statusbar.notification.icon.domain.interactor.AlwaysOnDisplayNotificationIconsInteractor
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerViewModel.ColorLookup
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerViewModel.IconColors
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerViewModel.IconsViewData
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.ui.AnimatableEvent
import com.android.systemui.util.ui.AnimatedValue
import com.android.systemui.util.ui.toAnimatedValueFlow
import com.android.systemui.util.ui.zip
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** View-model for the row of notification icons displayed on the always-on display. */
@SysUISingleton
class NotificationIconContainerAlwaysOnDisplayViewModel
@Inject
constructor(
    configuration: ConfigurationState,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val dozeParameters: DozeParameters,
    private val featureFlags: FeatureFlagsClassic,
    iconsInteractor: AlwaysOnDisplayNotificationIconsInteractor,
    keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val notificationsKeyguardInteractor: NotificationsKeyguardInteractor,
    screenOffAnimationController: ScreenOffAnimationController,
    shadeInteractor: ShadeInteractor,
) : NotificationIconContainerViewModel {

    override val iconColors: Flow<ColorLookup> =
        configuration.getColorAttr(R.attr.wallpaperTextColor, DEFAULT_AOD_ICON_COLOR).map { tint ->
            ColorLookup { IconColorsImpl(tint) }
        }

    override val animationsEnabled: Flow<Boolean> =
        combine(
            shadeInteractor.isShadeTouchable,
            keyguardInteractor.isKeyguardVisible,
        ) { panelTouchesEnabled, isKeyguardVisible ->
            panelTouchesEnabled && isKeyguardVisible
        }

    override val isDozing: Flow<AnimatedValue<Boolean>> =
        keyguardTransitionInteractor.startedKeyguardTransitionStep
            // Determine if we're dozing based on the most recent transition
            .map { step: TransitionStep ->
                val isDozing = step.to == KeyguardState.AOD || step.to == KeyguardState.DOZING
                isDozing to step
            }
            // Only emit changes based on whether we've started or stopped dozing
            .distinctUntilChanged { (wasDozing, _), (isDozing, _) -> wasDozing != isDozing }
            // Determine whether we need to animate
            .map { (isDozing, step) ->
                val animate = step.to == KeyguardState.AOD || step.from == KeyguardState.AOD
                AnimatableEvent(isDozing, animate)
            }
            .distinctUntilChanged()
            .toAnimatedValueFlow()

    override val isVisible: Flow<AnimatedValue<Boolean>> =
        combine(
                keyguardTransitionInteractor.finishedKeyguardState.map { it != KeyguardState.GONE },
                deviceEntryInteractor.isBypassEnabled,
                areNotifsFullyHiddenAnimated(),
                isPulseExpandingAnimated(),
            ) {
                onKeyguard: Boolean,
                isBypassEnabled: Boolean,
                notifsFullyHidden: AnimatedValue<Boolean>,
                pulseExpanding: AnimatedValue<Boolean>,
                ->
                when {
                    // Hide the AOD icons if we're not in the KEYGUARD state unless the screen off
                    // animation is playing, in which case we want them to be visible if we're
                    // animating in the AOD UI and will be switching to KEYGUARD shortly.
                    !onKeyguard && !screenOffAnimationController.shouldShowAodIconsWhenShade() ->
                        AnimatedValue.NotAnimating(false)
                    else ->
                        zip(notifsFullyHidden, pulseExpanding) {
                            areNotifsFullyHidden,
                            isPulseExpanding,
                            ->
                            when {
                                // If we're bypassing, then we're visible
                                isBypassEnabled -> true
                                // If we are pulsing (and not bypassing), then we are hidden
                                isPulseExpanding -> false
                                // If notifs are fully gone, then we're visible
                                areNotifsFullyHidden -> true
                                // Otherwise, we're hidden
                                else -> false
                            }
                        }
                }
            }
            .distinctUntilChanged()

    override val iconsViewData: Flow<IconsViewData> =
        iconsInteractor.aodNotifs.map { entries ->
            IconsViewData(
                visibleKeys = entries.mapNotNull { it.toIconInfo(it.aodIcon) },
            )
        }

    /** Is there an expanded pulse, are we animating in response? */
    private fun isPulseExpandingAnimated(): Flow<AnimatedValue<Boolean>> {
        return notificationsKeyguardInteractor.isPulseExpanding
            .pairwise(initialValue = null)
            // If pulsing changes, start animating, unless it's the first emission
            .map { (prev, expanding) -> AnimatableEvent(expanding, startAnimating = prev != null) }
            .toAnimatedValueFlow()
    }

    /** Are notifications completely hidden from view, are we animating in response? */
    private fun areNotifsFullyHiddenAnimated(): Flow<AnimatedValue<Boolean>> {
        return notificationsKeyguardInteractor.areNotificationsFullyHidden
            .pairwise(initialValue = null)
            .sample(deviceEntryInteractor.isBypassEnabled) { (prev, fullyHidden), bypassEnabled ->
                val animate =
                    when {
                        // Don't animate for the first value
                        prev == null -> false
                        // Always animate if bypass is enabled.
                        bypassEnabled -> true
                        // If we're not bypassing and we're not going to AOD, then we're not
                        // animating.
                        !dozeParameters.alwaysOn -> false
                        // Don't animate when going to AOD if the display needs blanking.
                        dozeParameters.displayNeedsBlanking -> false
                        // We only want the appear animations to happen when the notifications
                        // get fully hidden, since otherwise the un-hide animation overlaps.
                        featureFlags.isEnabled(Flags.NEW_AOD_TRANSITION) -> true
                        else -> fullyHidden
                    }
                AnimatableEvent(fullyHidden, animate)
            }
            .toAnimatedValueFlow()
    }

    private class IconColorsImpl(override val tint: Int) : IconColors {
        override fun staticDrawableColor(viewBounds: Rect, isColorized: Boolean): Int = tint
    }

    companion object {
        @ColorInt private val DEFAULT_AOD_ICON_COLOR = Color.WHITE
    }
}

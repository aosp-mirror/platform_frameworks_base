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

import android.graphics.Rect
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.domain.interactor.NotificationsInteractor
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerViewModel.ColorLookup
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerViewModel.IconColors
import com.android.systemui.statusbar.phone.domain.interactor.DarkIconInteractor
import com.android.systemui.util.ui.AnimatedValue
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow

/** View-model for the row of notification icons displayed in the status bar, */
class NotificationIconContainerStatusBarViewModel
@Inject
constructor(
    darkIconInteractor: DarkIconInteractor,
    keyguardInteractor: KeyguardInteractor,
    notificationsInteractor: NotificationsInteractor,
    shadeInteractor: ShadeInteractor,
) : NotificationIconContainerViewModel {
    override val animationsEnabled: Flow<Boolean> =
        combine(
            shadeInteractor.isShadeTouchable,
            keyguardInteractor.isKeyguardShowing,
        ) { panelTouchesEnabled, isKeyguardShowing ->
            panelTouchesEnabled && !isKeyguardShowing
        }
    override val iconColors: Flow<ColorLookup> =
        combine(
            darkIconInteractor.tintAreas,
            darkIconInteractor.tintColor,
            // Included so that tints are re-applied after entries are changed.
            notificationsInteractor.notifications,
        ) { areas, tint, _ ->
            ColorLookup { viewBounds: Rect ->
                if (DarkIconDispatcher.isInAreas(areas, viewBounds)) {
                    IconColorsImpl(tint, areas)
                } else {
                    null
                }
            }
        }
    override val isDozing: Flow<AnimatedValue<Boolean>> = emptyFlow()
    override val isVisible: Flow<AnimatedValue<Boolean>> = emptyFlow()
    override fun completeDozeAnimation() {}
    override fun completeVisibilityAnimation() {}

    private class IconColorsImpl(
        override val tint: Int,
        private val areas: Collection<Rect>,
    ) : IconColors {
        override fun staticDrawableColor(viewBounds: Rect, isColorized: Boolean): Int {
            return if (isColorized && DarkIconDispatcher.isInAreas(areas, viewBounds)) {
                tint
            } else {
                DarkIconDispatcher.DEFAULT_ICON_TINT
            }
        }
    }
}

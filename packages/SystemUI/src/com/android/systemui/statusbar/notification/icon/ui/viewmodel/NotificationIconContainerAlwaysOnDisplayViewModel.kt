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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.icon.domain.interactor.AlwaysOnDisplayNotificationIconsInteractor
import com.android.systemui.util.ui.AnimatableEvent
import com.android.systemui.util.ui.AnimatedValue
import com.android.systemui.util.ui.toAnimatedValueFlow
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
    iconsInteractor: AlwaysOnDisplayNotificationIconsInteractor,
    keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    shadeInteractor: ShadeInteractor,
) {

    /** Are changes to the icon container animated? */
    val animationsEnabled: Flow<Boolean> =
        combine(
            shadeInteractor.isShadeTouchable,
            keyguardInteractor.isKeyguardVisible,
        ) { panelTouchesEnabled, isKeyguardVisible ->
            panelTouchesEnabled && isKeyguardVisible
        }

    /** Should icons be rendered in "dozing" mode? */
    val isDozing: Flow<AnimatedValue<Boolean>> =
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

    /** [NotificationIconsViewData] indicating which icons to display in the view. */
    val icons: Flow<NotificationIconsViewData> =
        iconsInteractor.aodNotifs.map { entries ->
            NotificationIconsViewData(
                visibleKeys = entries.mapNotNull { it.toIconInfo(it.aodIcon) },
            )
        }
}

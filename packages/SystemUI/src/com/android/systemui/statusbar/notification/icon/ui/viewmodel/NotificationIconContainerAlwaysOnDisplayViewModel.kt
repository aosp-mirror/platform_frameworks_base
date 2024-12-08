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

import android.content.res.Resources
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.icon.domain.interactor.AlwaysOnDisplayNotificationIconsInteractor
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** View-model for the row of notification icons displayed on the always-on display. */
@SysUISingleton
class NotificationIconContainerAlwaysOnDisplayViewModel
@Inject
constructor(
    @Background bgContext: CoroutineContext,
    iconsInteractor: AlwaysOnDisplayNotificationIconsInteractor,
    keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    @ShadeDisplayAware resources: Resources,
    shadeInteractor: ShadeInteractor,
) {
    private val maxIcons = resources.getInteger(R.integer.max_notif_icons_on_aod)

    /** Are changes to the icon container animated? */
    val areContainerChangesAnimated: Flow<Boolean> =
        combine(shadeInteractor.isShadeTouchable, keyguardInteractor.isKeyguardVisible) {
                panelTouchesEnabled,
                isKeyguardVisible ->
                panelTouchesEnabled && isKeyguardVisible
            }
            .flowOn(bgContext)
            .conflate()
            .distinctUntilChanged()

    /** Amount of a "white" tint to be applied to the icons. */
    val tintAlpha: Flow<Float> =
        combine(
                keyguardTransitionInteractor.transitionValue(KeyguardState.AOD).onStart {
                    emit(0f)
                },
                keyguardTransitionInteractor.transitionValue(KeyguardState.DOZING).onStart {
                    emit(0f)
                },
            ) { aodAmt, dozeAmt ->
                aodAmt + dozeAmt // If transitioning between them, they should sum to 1f
            }
            .flowOn(bgContext)
            .conflate()
            .distinctUntilChanged()

    /** Are notification icons animated (ex: animated gif)? */
    val areIconAnimationsEnabled: Flow<Boolean> =
        keyguardTransitionInteractor
            .isFinishedInStateWhere {
                // Don't animate icons when we're on AOD / dozing
                it != KeyguardState.AOD && it != KeyguardState.DOZING
            }
            .onStart { emit(true) }
            .flowOn(bgContext)
            .conflate()
            .distinctUntilChanged()

    /** [NotificationIconsViewData] indicating which icons to display in the view. */
    val icons: Flow<NotificationIconsViewData> =
        iconsInteractor.aodNotifs
            .map { entries ->
                NotificationIconsViewData(
                    visibleIcons = entries.mapNotNull { it.toIconInfo(it.aodIcon) },
                    iconLimit = maxIcons,
                )
            }
            .flowOn(bgContext)
            .conflate()
            .distinctUntilChanged()
}

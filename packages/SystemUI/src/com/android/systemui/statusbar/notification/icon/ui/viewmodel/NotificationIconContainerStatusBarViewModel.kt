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
import android.graphics.Rect
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationIconInteractor
import com.android.systemui.statusbar.notification.icon.domain.interactor.StatusBarNotificationIconsInteractor
import com.android.systemui.statusbar.phone.domain.interactor.DarkIconInteractor
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.ui.AnimatableEvent
import com.android.systemui.util.ui.AnimatedValue
import com.android.systemui.util.ui.toAnimatedValueFlow
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** View-model for the row of notification icons displayed in the status bar, */
class NotificationIconContainerStatusBarViewModel
@Inject
constructor(
    darkIconInteractor: DarkIconInteractor,
    iconsInteractor: StatusBarNotificationIconsInteractor,
    headsUpIconInteractor: HeadsUpNotificationIconInteractor,
    keyguardInteractor: KeyguardInteractor,
    notificationsInteractor: ActiveNotificationsInteractor,
    @Main resources: Resources,
    shadeInteractor: ShadeInteractor,
) {

    private val maxIcons = resources.getInteger(R.integer.max_notif_static_icons)

    /** Are changes to the icon container animated? */
    val animationsEnabled: Flow<Boolean> =
        combine(
            shadeInteractor.isShadeTouchable,
            keyguardInteractor.isKeyguardShowing,
        ) { panelTouchesEnabled, isKeyguardShowing ->
            panelTouchesEnabled && !isKeyguardShowing
        }

    /** The colors with which to display the notification icons. */
    val iconColors: Flow<NotificationIconColorLookup> =
        combine(
            darkIconInteractor.tintAreas,
            darkIconInteractor.tintColor,
            // Included so that tints are re-applied after entries are changed.
            notificationsInteractor.topLevelRepresentativeNotifications,
        ) { areas, tint, _ ->
            NotificationIconColorLookup { viewBounds: Rect ->
                if (DarkIconDispatcher.isInAreas(areas, viewBounds)) {
                    IconColorsImpl(tint, areas)
                } else {
                    null
                }
            }
        }

    /** [NotificationIconsViewData] indicating which icons to display in the view. */
    val icons: Flow<NotificationIconsViewData> =
        iconsInteractor.statusBarNotifs.map { entries ->
            NotificationIconsViewData(
                visibleIcons = entries.mapNotNull { it.toIconInfo(it.statusBarIcon) },
                iconLimit = maxIcons,
            )
        }

    /** An Icon to show "isolated" in the IconContainer. */
    val isolatedIcon: Flow<AnimatedValue<NotificationIconInfo?>> =
        headsUpIconInteractor.isolatedNotification
            .combine(icons) { isolatedNotif, iconsViewData ->
                isolatedNotif?.let {
                    iconsViewData.visibleIcons.firstOrNull { it.notifKey == isolatedNotif }
                }
            }
            .pairwise(initialValue = null)
            .distinctUntilChanged()
            .sample(shadeInteractor.shadeExpansion) { (prev, iconInfo), shadeExpansion ->
                val animate =
                    when {
                        iconInfo?.notifKey == prev?.notifKey -> false
                        iconInfo == null || prev == null -> shadeExpansion == 0f
                        else -> false
                    }
                AnimatableEvent(iconInfo, animate)
            }
            .toAnimatedValueFlow()

    /** Location to show an isolated icon, if there is one. */
    val isolatedIconLocation: Flow<Rect> =
        headsUpIconInteractor.isolatedIconLocation.filterNotNull()

    private class IconColorsImpl(
        override val tint: Int,
        private val areas: Collection<Rect>,
    ) : NotificationIconColors {
        override fun staticDrawableColor(viewBounds: Rect): Int {
            return if (DarkIconDispatcher.isInAreas(areas, viewBounds)) {
                tint
            } else {
                DarkIconDispatcher.DEFAULT_ICON_TINT
            }
        }
    }
}

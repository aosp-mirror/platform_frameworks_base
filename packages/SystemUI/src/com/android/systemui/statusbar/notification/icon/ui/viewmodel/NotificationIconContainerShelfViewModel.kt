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

import com.android.systemui.statusbar.notification.icon.domain.interactor.NotificationIconsInteractor
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerViewModel.ColorLookup
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerViewModel.IconsViewData
import com.android.systemui.util.ui.AnimatedValue
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** View-model for the overflow row of notification icons displayed in the notification shade. */
class NotificationIconContainerShelfViewModel
@Inject
constructor(
    interactor: NotificationIconsInteractor,
) : NotificationIconContainerViewModel {
    override val animationsEnabled: Flow<Boolean> = flowOf(true)
    override val isDozing: Flow<AnimatedValue<Boolean>> = emptyFlow()
    override val isVisible: Flow<AnimatedValue<Boolean>> = emptyFlow()
    override fun completeDozeAnimation() {}
    override fun completeVisibilityAnimation() {}
    override val iconColors: Flow<ColorLookup> = emptyFlow()

    override val iconsViewData: Flow<IconsViewData> =
        interactor.filteredNotifSet().map { entries ->
            IconsViewData(
                visibleKeys = entries.mapNotNull { it.toIconInfo(it.shelfIcon) },
            )
        }
}

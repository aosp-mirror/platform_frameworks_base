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
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData.LimitType
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** View-model for the overflow row of notification icons displayed in the notification shade. */
class NotificationIconContainerShelfViewModel
@Inject
constructor(
    interactor: NotificationIconsInteractor,
) {
    /** [NotificationIconsViewData] indicating which icons to display in the view. */
    val icons: Flow<NotificationIconsViewData> =
        interactor.filteredNotifSet().map { entries ->
            var firstAmbient = 0
            val visibleKeys = buildList {
                for (entry in entries) {
                    entry.toIconInfo(entry.shelfIcon)?.let { info ->
                        add(info)
                        // NOTE: we assume that all ambient notifications will be at the end of the
                        // list
                        if (!entry.isAmbient) {
                            firstAmbient++
                        }
                    }
                }
            }
            NotificationIconsViewData(
                visibleKeys,
                iconLimit = firstAmbient,
                limitType = LimitType.MaximumIndex,
            )
        }
}

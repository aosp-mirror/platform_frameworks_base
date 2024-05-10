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

package com.android.systemui.statusbar.notification.shelf.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerShelfViewModel
import com.android.systemui.statusbar.notification.row.ui.viewmodel.ActivatableNotificationViewModel
import com.android.systemui.statusbar.notification.shelf.domain.interactor.NotificationShelfInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** ViewModel for [NotificationShelf]. */
@SysUISingleton
class NotificationShelfViewModel
@Inject
constructor(
    private val interactor: NotificationShelfInteractor,
    activatableViewModel: ActivatableNotificationViewModel,
    val icons: NotificationIconContainerShelfViewModel,
) : ActivatableNotificationViewModel by activatableViewModel {
    /** Is the shelf allowed to be clickable when it has content? */
    val isClickable: Flow<Boolean>
        get() = interactor.isShowingOnKeyguard

    /** Is the shelf allowed to modify the color of notifications in the host layout? */
    val canModifyColorOfNotifications: Flow<Boolean>
        get() = interactor.isShelfStatic.map { static -> !static }

    /** Notifies that the user has clicked the shelf. */
    fun onShelfClicked() {
        interactor.goToLockedShadeFromShelf()
    }
}

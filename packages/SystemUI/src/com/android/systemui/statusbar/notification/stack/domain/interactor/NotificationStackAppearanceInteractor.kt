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

package com.android.systemui.statusbar.notification.stack.domain.interactor

import com.android.systemui.common.shared.model.SharedNotificationContainerPosition
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.stack.data.repository.NotificationStackAppearanceRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An interactor which controls the appearance of the NSSL */
@SysUISingleton
class NotificationStackAppearanceInteractor
@Inject
constructor(
    private val repository: NotificationStackAppearanceRepository,
) {
    /** The position of the notification stack in the current scene */
    val stackPosition: StateFlow<SharedNotificationContainerPosition>
        get() = repository.stackPosition.asStateFlow()

    /** Sets the position of the notification stack in the current scene */
    fun setStackPosition(position: SharedNotificationContainerPosition) {
        check(position.top <= position.bottom) { "Invalid position: $position" }
        repository.stackPosition.value = position
    }
}

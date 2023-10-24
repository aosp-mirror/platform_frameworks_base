/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.domain.interactor

import android.graphics.Rect
import com.android.systemui.statusbar.notification.data.repository.HeadsUpNotificationIconViewStateRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Domain logic pertaining to heads up notification icons. */
class HeadsUpNotificationIconInteractor
@Inject
constructor(
    private val repository: HeadsUpNotificationIconViewStateRepository,
) {
    /** Notification key for a notification icon to show isolated, or `null` if none. */
    val isolatedIconLocation: Flow<Rect?> = repository.isolatedIconLocation

    /** Area to display the isolated notification, or `null` if none. */
    val isolatedNotification: Flow<String?> = repository.isolatedNotification

    /** Updates the location where isolated notification icons are shown. */
    fun setIsolatedIconLocation(rect: Rect?) {
        repository.isolatedIconLocation.value = rect
    }

    /** Updates which notification will have its icon displayed isolated. */
    fun setIsolatedIconNotificationKey(key: String?) {
        repository.isolatedNotification.value = key
    }
}

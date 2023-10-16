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
package com.android.systemui.statusbar.notification.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Repository of "active" notifications in the notification list.
 *
 * This repository serves as the boundary between the
 * [com.android.systemui.statusbar.notification.collection.NotifPipeline] and the modern
 * notifications presentation codebase.
 */
@SysUISingleton
class ActiveNotificationListRepository @Inject constructor() {
    /**
     * Notifications actively presented to the user in the notification stack.
     *
     * @see com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderListListener
     */
    val activeNotifications = MutableStateFlow(emptyMap<String, ActiveNotificationModel>())

    /** Are any already-seen notifications currently filtered out of the active list? */
    val hasFilteredOutSeenNotifications = MutableStateFlow(false)
}

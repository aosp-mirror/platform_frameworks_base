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
package com.android.systemui.statusbar.notification.domain.interactor

import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.data.repository.MutableNotificationStackRepository
import javax.inject.Inject

/**
 * Logic for passing information from the
 * [com.android.systemui.statusbar.notification.collection.NotifPipeline] to the presentation
 * layers.
 */
class RenderNotificationListInteractor
@Inject
constructor(
    private val repository: MutableNotificationStackRepository,
) {
    /**
     * Sets the current list of rendered notification entries as displayed in the notification
     * stack.
     *
     * @see com.android.systemui.statusbar.notification.data.repository.NotificationStackRepository.renderedEntries
     */
    fun setRenderedList(entries: List<ListEntry>) {
        repository.renderedEntries.value = entries.toList()
    }
}

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

package com.android.systemui.statusbar.notification.icon.ui.viewbinder

import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.statusbar.notification.collection.NotifCollection
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder.IconViewStore
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder.bindIcons
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerShelfViewModel
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.ui.SystemBarUtilsState
import javax.inject.Inject

/** Binds a [NotificationIconContainer] to a [NotificationIconContainerShelfViewModel]. */
class NotificationIconContainerShelfViewBinder
@Inject
constructor(
    private val viewModel: NotificationIconContainerShelfViewModel,
    private val configuration: ConfigurationState,
    private val systemBarUtilsState: SystemBarUtilsState,
    private val failureTracker: StatusBarIconViewBindingFailureTracker,
    private val viewStore: ShelfNotificationIconViewStore,
) {
    suspend fun bind(view: NotificationIconContainer) {
        viewModel.icons.bindIcons(
            logTag = "shelf",
            view = view,
            configuration = configuration,
            systemBarUtilsState = systemBarUtilsState,
            notifyBindingFailures = { failureTracker.shelfFailures = it },
            viewStore = viewStore,
        )
    }
}

/** [IconViewStore] for the [com.android.systemui.statusbar.NotificationShelf] */
class ShelfNotificationIconViewStore @Inject constructor(notifCollection: NotifCollection) :
    IconViewStore by (notifCollection.iconViewStoreBy { it.shelfIcon })

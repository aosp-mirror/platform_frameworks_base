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

import androidx.lifecycle.lifecycleScope
import com.android.app.tracing.traceSection
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.notification.collection.NotifCollection
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder.IconViewStore
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.ui.SystemBarUtilsState
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import com.android.app.tracing.coroutines.launchTraced as launch

/** Binds a [NotificationIconContainer] to a [NotificationIconContainerStatusBarViewModel]. */
class NotificationIconContainerStatusBarViewBinder
@Inject
constructor(
    private val viewModel: NotificationIconContainerStatusBarViewModel,
    private val configuration: ConfigurationState,
    private val systemBarUtilsState: SystemBarUtilsState,
    private val failureTracker: StatusBarIconViewBindingFailureTracker,
    private val viewStore: StatusBarNotificationIconViewStore,
) {
    fun bindWhileAttached(view: NotificationIconContainer): DisposableHandle {
        return traceSection("NICStatusBar#bindWhileAttached") {
            view.repeatWhenAttached {
                lifecycleScope.launch {
                    NotificationIconContainerViewBinder.bind(
                        view = view,
                        viewModel = viewModel,
                        configuration = configuration,
                        systemBarUtilsState = systemBarUtilsState,
                        failureTracker = failureTracker,
                        viewStore = viewStore,
                    )
                }
            }
        }
    }
}

/** [IconViewStore] for the status bar. */
class StatusBarNotificationIconViewStore @Inject constructor(notifCollection: NotifCollection) :
    IconViewStore by (notifCollection.iconViewStoreBy { it.statusBarIcon })

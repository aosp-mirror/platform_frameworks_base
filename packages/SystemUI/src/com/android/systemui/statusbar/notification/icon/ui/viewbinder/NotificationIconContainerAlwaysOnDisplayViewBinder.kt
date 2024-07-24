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
import com.android.systemui.keyguard.ui.binder.KeyguardRootViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.notification.collection.NotifCollection
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder.IconViewStore
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.ui.SystemBarUtilsState
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch

/** Binds a [NotificationIconContainer] to a [NotificationIconContainerAlwaysOnDisplayViewModel]. */
class NotificationIconContainerAlwaysOnDisplayViewBinder
@Inject
constructor(
    private val viewModel: NotificationIconContainerAlwaysOnDisplayViewModel,
    private val keyguardRootViewModel: KeyguardRootViewModel,
    private val configuration: ConfigurationState,
    private val failureTracker: StatusBarIconViewBindingFailureTracker,
    private val screenOffAnimationController: ScreenOffAnimationController,
    private val systemBarUtilsState: SystemBarUtilsState,
    private val viewStore: AlwaysOnDisplayNotificationIconViewStore,
) {
    fun bindWhileAttached(view: NotificationIconContainer): DisposableHandle {
        return traceSection("NICAlwaysOnDisplay#bindWhileAttached") {
            view.repeatWhenAttached {
                lifecycleScope.launch {
                    launch {
                        NotificationIconContainerViewBinder.bind(
                            view = view,
                            viewModel = viewModel,
                            configuration = configuration,
                            systemBarUtilsState = systemBarUtilsState,
                            failureTracker = failureTracker,
                            viewStore = viewStore,
                        )
                    }
                    launch {
                        KeyguardRootViewBinder.bindAodNotifIconVisibility(
                            view = view,
                            isVisible = keyguardRootViewModel.isNotifIconContainerVisible,
                            configuration = configuration,
                            screenOffAnimationController = screenOffAnimationController,
                        )
                    }
                }
            }
        }
    }
}

/** [IconViewStore] for the always-on display. */
class AlwaysOnDisplayNotificationIconViewStore
@Inject
constructor(notifCollection: NotifCollection) :
    IconViewStore by (notifCollection.iconViewStoreBy { it.aodIcon })

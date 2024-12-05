/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.communal.widgets

import android.content.Context
import com.android.server.servicewatcher.ServiceWatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserTracker
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Supplies details about the [GlanceableHubWidgetManagerService] that the [ServiceWatcher] should
 * bind to. Currently the service should only be bound if the current user is the main user.
 */
@SysUISingleton
class GlanceableHubWidgetManagerServiceSupplier
@Inject
constructor(
    @Application private val context: Context,
    @Background private val bgExecutor: Executor,
    private val userTracker: UserTracker,
) : ServiceWatcher.ServiceSupplier<GlanceableHubWidgetManagerServiceInfo?>, UserTracker.Callback {

    private var userAboutToSwitch = false
    private var listener: ServiceWatcher.ServiceChangedListener? = null

    override fun getServiceInfo(): GlanceableHubWidgetManagerServiceInfo? {
        return GlanceableHubWidgetManagerServiceInfo(context, userTracker.userHandle)
    }

    override fun hasMatchingService(): Boolean {
        // The service becomes unavailable immediately before a user switching is about to happen
        // so that it is disconnected before the user process is terminated.
        // It is also only available if the current user is the main user.
        return !userAboutToSwitch && userTracker.userInfo.isMain
    }

    override fun register(listener: ServiceWatcher.ServiceChangedListener?) {
        this.listener = listener
        userTracker.addCallback(this, bgExecutor)
    }

    override fun unregister() {
        listener = null
        userTracker.removeCallback(this)
    }

    override fun onBeforeUserSwitching(newUser: Int) {
        userAboutToSwitch = true
        listener?.onServiceChanged()
    }

    override fun onUserChanged(newUser: Int, userContext: Context) {
        userAboutToSwitch = false
        listener?.onServiceChanged()
    }
}

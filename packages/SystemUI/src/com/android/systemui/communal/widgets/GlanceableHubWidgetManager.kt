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

import android.os.IBinder
import com.android.server.servicewatcher.ServiceWatcher.ServiceListener
import com.android.systemui.communal.shared.model.GlanceableHubMultiUserHelper
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import javax.inject.Inject

/**
 * Manages updates to Glanceable Hub widgets and requests to edit them from the headless system
 * user.
 *
 * It communicates with the remote [GlanceableHubWidgetManagerService] which runs in the foreground
 * user, and abstracts the IPC details from the rest of the system.
 */
@SysUISingleton
class GlanceableHubWidgetManager
@Inject
constructor(
    glanceableHubMultiUserHelper: GlanceableHubMultiUserHelper,
    @CommunalLog logBuffer: LogBuffer,
    serviceWatcherFactory: ServiceWatcherFactory<GlanceableHubWidgetManagerServiceInfo?>,
) : ServiceListener<GlanceableHubWidgetManagerServiceInfo?> {

    init {
        // The manager should only be used in the headless system user.
        glanceableHubMultiUserHelper.assertInHeadlessSystemUser()
    }

    private val logger = Logger(logBuffer, TAG)

    private val serviceWatcher by lazy { serviceWatcherFactory.create(this) }

    fun register() {
        serviceWatcher.register()
    }

    fun unregister() {
        serviceWatcher.unregister()
    }

    override fun onBind(binder: IBinder?, serviceInfo: GlanceableHubWidgetManagerServiceInfo?) {
        logger.i("Service bound")
    }

    override fun onUnbind() {
        logger.i("Service unbound")
    }

    companion object {
        private const val TAG = "GlanceableHubWidgetManager"
    }
}

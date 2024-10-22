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

import android.content.ComponentName
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.os.UserHandle
import com.android.server.servicewatcher.ServiceWatcher

/**
 * Information about the [GlanceableHubWidgetManagerServiceInfo] used for binding with the
 * [ServiceWatcher].
 */
class GlanceableHubWidgetManagerServiceInfo(context: Context, userHandle: UserHandle) :
    ServiceWatcher.BoundServiceInfo(
        /* action= */ null,
        UserHandle.getUid(userHandle.identifier, UserHandle.getCallingAppId()),
        ComponentName(context.packageName, GlanceableHubWidgetManagerService::class.java.name),
        BIND_AUTO_CREATE,
    )

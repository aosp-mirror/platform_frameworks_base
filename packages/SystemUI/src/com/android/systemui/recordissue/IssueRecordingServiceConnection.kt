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

package com.android.systemui.recordissue

import android.content.Intent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.settings.UserContextProvider
import com.android.traceur.MessageConstants.SYSTEM_UI_PACKAGE_NAME
import javax.inject.Inject

/**
 * It is necessary to bind to IssueRecordingService from the Record Issue Tile because there are
 * instances where this service is not created in the same user profile as the record issue tile
 * aka, headless system user mode. In those instances, the TraceurConnection will be considered a
 * leak in between notification actions unless the tile is bound to this service to keep it alive.
 */
class IssueRecordingServiceConnection(userContextProvider: UserContextProvider) :
    UserAwareConnection(
        userContextProvider,
        Intent().setClassName(SYSTEM_UI_PACKAGE_NAME, IssueRecordingService::class.java.name),
    ) {
    @SysUISingleton
    class Provider @Inject constructor(private val userContextProvider: UserContextProvider) {
        fun create() = IssueRecordingServiceConnection(userContextProvider)
    }
}

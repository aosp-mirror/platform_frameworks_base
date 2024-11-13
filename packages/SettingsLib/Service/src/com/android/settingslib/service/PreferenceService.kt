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

package com.android.settingslib.service

import com.android.settingslib.graph.PreferenceSetterApiHandler
import com.android.settingslib.ipc.ApiHandler
import com.android.settingslib.ipc.MessengerService
import com.android.settingslib.ipc.PermissionChecker
import com.android.settingslib.preference.PreferenceScreenProvider

/**
 * Preference service providing a bunch of APIs.
 *
 * In AndroidManifest.xml, the <service> must specify <intent-filter> with action
 * [PREFERENCE_SERVICE_ACTION].
 */
open class PreferenceService(
    permissionChecker: PermissionChecker = PermissionChecker { _, _, _ -> true },
    preferenceScreenProviders: Set<Class<out PreferenceScreenProvider>> = setOf(),
    enablePreferenceSetterApi: Boolean = false,
    name: String = "PreferenceService",
) :
    MessengerService(
        mutableListOf<ApiHandler<*, *>>().apply {
            add(PreferenceGraphApi(preferenceScreenProviders))
            if (enablePreferenceSetterApi) add(PreferenceSetterApiHandler(API_PREFERENCE_SETTER))
        },
        permissionChecker,
        name,
    )

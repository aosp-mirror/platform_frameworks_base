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

import com.android.settingslib.graph.GetPreferenceGraphRequest
import com.android.settingslib.graph.PreferenceGetterApiHandler
import com.android.settingslib.graph.PreferenceGetterRequest
import com.android.settingslib.graph.PreferenceSetterApiHandler
import com.android.settingslib.graph.PreferenceSetterRequest
import com.android.settingslib.ipc.ApiHandler
import com.android.settingslib.ipc.ApiPermissionChecker
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
    name: String = "PreferenceService",
    permissionChecker: PermissionChecker = PermissionChecker { _, _, _ -> true },
    preferenceScreenProviders: Set<Class<out PreferenceScreenProvider>> = setOf(),
    graphPermissionChecker: ApiPermissionChecker<GetPreferenceGraphRequest>? = null,
    setterPermissionChecker: ApiPermissionChecker<PreferenceSetterRequest>? = null,
    getterPermissionChecker: ApiPermissionChecker<PreferenceGetterRequest>? = null,
    vararg apiHandlers: ApiHandler<*, *>,
) :
    MessengerService(
        mutableListOf<ApiHandler<*, *>>().apply {
            graphPermissionChecker?.let { add(PreferenceGraphApi(preferenceScreenProviders, it)) }
            setterPermissionChecker?.let {
                add(PreferenceSetterApiHandler(API_PREFERENCE_SETTER, it))
            }
            getterPermissionChecker?.let {
                add(PreferenceGetterApiHandler(API_PREFERENCE_GETTER, it))
            }
            addAll(apiHandlers)
        },
        permissionChecker,
        name,
    )

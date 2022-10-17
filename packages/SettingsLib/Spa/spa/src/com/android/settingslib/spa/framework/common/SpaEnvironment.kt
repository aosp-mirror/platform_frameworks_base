/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.framework.common

import android.app.Activity
import android.util.Log

private const val TAG = "SpaEnvironment"

object SpaEnvironmentFactory {
    private var spaEnvironment: SpaEnvironment? = null

    var instance: SpaEnvironment
        get() {
            if (spaEnvironment == null)
                throw UnsupportedOperationException("Spa environment is not set")
            return spaEnvironment!!
        }
        set(env: SpaEnvironment) {
            if (spaEnvironment != null) {
                Log.w(TAG, "Spa environment is already set, ignore the latter one.")
                return
            }
            spaEnvironment = env
        }
}

abstract class SpaEnvironment {
    abstract val pageProviderRepository: Lazy<SettingsPageProviderRepository>

    val entryRepository = lazy { SettingsEntryRepository(pageProviderRepository.value) }

    open val browseActivityClass: Class<out Activity>? = null

    open val entryProviderAuthorities: String? = null

    open val logger: SpaLogger = object : SpaLogger {}

    // TODO: add other environment setup here.
}

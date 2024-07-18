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
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private const val TAG = "SpaEnvironment"

object SpaEnvironmentFactory {
    private var spaEnvironment: SpaEnvironment? = null

    fun reset() {
        spaEnvironment = null
    }

    fun reset(env: SpaEnvironment) {
        spaEnvironment = env
        Log.d(TAG, "reset")
    }

    @Composable
    fun resetForPreview() {
        val context = LocalContext.current
        spaEnvironment = object : SpaEnvironment(context) {
            override val pageProviderRepository = lazy {
                SettingsPageProviderRepository(
                    allPageProviders = emptyList(),
                    rootPages = emptyList()
                )
            }
        }
        Log.d(TAG, "resetForPreview")
    }

    fun isReady(): Boolean {
        return spaEnvironment != null
    }

    val instance: SpaEnvironment
        get() {
            if (spaEnvironment == null)
                throw UnsupportedOperationException("Spa environment is not set")
            return spaEnvironment!!
        }
}

abstract class SpaEnvironment(context: Context) {
    abstract val pageProviderRepository: Lazy<SettingsPageProviderRepository>

    val entryRepository = lazy { SettingsEntryRepository(pageProviderRepository.value) }

    // The application context. Use local context as fallback when applicationContext is not
    // available (e.g. in Robolectric test).
    val appContext: Context = context.applicationContext ?: context

    // Set your SpaLogger implementation, for any SPA events logging.
    open val logger: SpaLogger = object : SpaLogger {}

    // Specify class name of browse activity, which is used to
    // generate the necessary intents.
    open val browseActivityClass: Class<out Activity>? = null

    // Specify provider authorities for debugging purpose.
    open val searchProviderAuthorities: String? = null

    // TODO: add other environment setup here.
    companion object {
        /**
         * Whether debug mode is on or off.
         *
         * If set to true, this will also enable all the pages under development (allows browsing
         * and searching).
         */
        const val IS_DEBUG = false
    }
}

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

package com.android.settingslib.preference

import android.content.Context
import androidx.preference.PreferenceScreen

/**
 * Interface to provide [PreferenceScreen].
 *
 * When implemented by Activity/Fragment, the Activity/Fragment [Context] APIs (e.g. `getContext()`,
 * `getActivity()`) MUST not be used: preference screen creation could happen in background service,
 * where the Activity/Fragment lifecycle callbacks (`onCreate`, `onDestroy`, etc.) are not invoked
 * and context APIs return null.
 */
interface PreferenceScreenProvider {

    /**
     * Creates [PreferenceScreen].
     *
     * Preference screen creation could happen in background service. The implementation MUST use
     * [PreferenceScreenFactory.context] to obtain context.
     */
    fun createPreferenceScreen(factory: PreferenceScreenFactory): PreferenceScreen?
}

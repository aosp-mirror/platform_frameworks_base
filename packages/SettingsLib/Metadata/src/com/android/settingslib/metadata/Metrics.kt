/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.settingslib.metadata

import android.content.Context

/** Metrics logger for preference actions triggered by user interaction. */
interface PreferenceUiActionMetricsLogger {

    /**
     * Logs preference value change due to user interaction.
     *
     * Note: Preference value changed by external Set is excluded.
     */
    fun logPreferenceValueChange(
        screen: PreferenceScreenMetadata,
        preference: PreferenceMetadata,
        value: Any?,
    )
}

/** Metrics logger for preference remote operations (e.g. external get/set). */
interface PreferenceRemoteOpMetricsLogger {

    /** Logs get preference metadata operation. */
    fun logGetterApi(
        context: Context,
        callingUid: Int,
        preferenceCoordinate: PreferenceCoordinate,
        screen: PreferenceScreenMetadata?,
        preference: PreferenceMetadata?,
        errorCode: Int,
        latencyMs: Long,
    )

    /** Logs set preference value operation. */
    fun logSetterApi(
        context: Context,
        callingUid: Int,
        preferenceCoordinate: PreferenceCoordinate,
        screen: PreferenceScreenMetadata?,
        preference: PreferenceMetadata?,
        errorCode: Int,
        latencyMs: Long,
    )

    /** Logs get preference graph operation. */
    fun logGraphApi(context: Context, callingUid: Int, success: Boolean, latencyMs: Long)
}

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

import android.content.SharedPreferences
import com.android.traceur.PresetTraceConfigs.TraceOptions
import com.android.traceur.PresetTraceConfigs.getDefaultConfig
import com.android.traceur.TraceConfig

/**
 * This class encapsulates the values that go into a customized record issue trace config, part of
 * the RecordIssueTile feature. This class stores the last configuration chosen by power users.
 */
class CustomTraceState(private val prefs: SharedPreferences) {

    private var enabledTags: Set<String>?
        get() = prefs.getStringSet(KEY_TAGS, getDefaultConfig().tags) ?: getDefaultConfig().tags
        set(value) = prefs.edit().putStringSet(KEY_TAGS, value).apply()

    var traceConfig: TraceConfig
        get() = TraceConfig(options, enabledTags)
        set(value) {
            enabledTags = value.tags
            options = value.options
        }

    private var options: TraceOptions
        get() =
            TraceOptions(
                prefs.getInt(KEY_CUSTOM_BUFFER_SIZE_KB, getDefaultConfig().bufferSizeKb),
                prefs.getBoolean(KEY_WINSCOPE, getDefaultConfig().winscope),
                prefs.getBoolean(KEY_APPS, getDefaultConfig().apps),
                prefs.getBoolean(KEY_LONG_TRACE, getDefaultConfig().longTrace),
                prefs.getBoolean(KEY_ATTACH_TO_BUGREPORT, getDefaultConfig().attachToBugreport),
                prefs.getInt(KEY_LONG_TRACE_SIZE_MB, getDefaultConfig().maxLongTraceSizeMb),
                prefs.getInt(
                    KEY_LONG_TRACE_DURATION_MINUTES,
                    getDefaultConfig().maxLongTraceDurationMinutes
                ),
            )
        set(value) {
            prefs
                .edit()
                .putInt(KEY_CUSTOM_BUFFER_SIZE_KB, value.bufferSizeKb)
                .putBoolean(KEY_WINSCOPE, value.winscope)
                .putBoolean(KEY_APPS, value.apps)
                .putBoolean(KEY_LONG_TRACE, value.longTrace)
                .putBoolean(KEY_ATTACH_TO_BUGREPORT, value.attachToBugreport)
                .putInt(KEY_LONG_TRACE_SIZE_MB, value.maxLongTraceSizeMb)
                .putInt(KEY_LONG_TRACE_DURATION_MINUTES, value.maxLongTraceDurationMinutes)
                .apply()
        }

    companion object {
        private const val KEY_CUSTOM_BUFFER_SIZE_KB = "key_bufferSizeKb"
        private const val KEY_WINSCOPE = "key_winscope"
        private const val KEY_APPS = "key_apps"
        private const val KEY_LONG_TRACE = "key_longTrace"
        private const val KEY_ATTACH_TO_BUGREPORT = "key_attachToBugReport"
        private const val KEY_LONG_TRACE_SIZE_MB = "key_maxLongTraceSizeMb"
        private const val KEY_LONG_TRACE_DURATION_MINUTES = "key_maxLongTraceDurationInMinutes"
        private const val KEY_TAGS = "key_tags"
    }
}

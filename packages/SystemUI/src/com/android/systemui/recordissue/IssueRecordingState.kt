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

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.recordissue.RecordIssueModule.Companion.TILE_SPEC
import com.android.systemui.res.R
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.traceur.PresetTraceConfigs
import com.android.traceur.TraceConfig
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

@SysUISingleton
class IssueRecordingState
@Inject
constructor(
    userTracker: UserTracker,
    userFileManager: UserFileManager,
) {

    private val prefs =
        userFileManager.getSharedPreferences(TILE_SPEC, Context.MODE_PRIVATE, userTracker.userId)

    val customTraceState = CustomTraceState(prefs)

    var takeBugreport
        get() = prefs.getBoolean(KEY_TAKE_BUG_REPORT, false)
        set(value) = prefs.edit().putBoolean(KEY_TAKE_BUG_REPORT, value).apply()

    var recordScreen
        get() = prefs.getBoolean(KEY_RECORD_SCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_RECORD_SCREEN, value).apply()

    var hasUserApprovedScreenRecording
        get() = prefs.getBoolean(HAS_APPROVED_SCREEN_RECORDING, false)
        private set(value) = prefs.edit().putBoolean(HAS_APPROVED_SCREEN_RECORDING, value).apply()

    // Store the index of the issue type because res ids are generated at compile time and change
    // in value from one build to another. The index will not change between package versions.
    private var issueTypeIndex: Int
        get() = prefs.getInt(KEY_ISSUE_TYPE_INDEX, ISSUE_TYPE_NOT_SET)
        set(value) = prefs.edit().putInt(KEY_ISSUE_TYPE_INDEX, value).apply()

    var issueTypeRes
        get() =
            // If the user has never used the record issue tile, we don't show a default issue type
            if (issueTypeIndex == ISSUE_TYPE_NOT_SET) ISSUE_TYPE_NOT_SET
            else ALL_ISSUE_TYPES.keys.toIntArray()[issueTypeIndex]
        set(value) {
            issueTypeIndex = ALL_ISSUE_TYPES.keys.toIntArray().indexOf(value)
        }

    val traceConfig: TraceConfig
        get() = ALL_ISSUE_TYPES[issueTypeRes] ?: customTraceState.traceConfig

    // The 1st part of the title before the ": " is the tag, and the 2nd part is the description
    var tagTitles: Set<String>
        get() = prefs.getStringSet(KEY_TAG_TITLES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_TAG_TITLES, value).apply()

    private val listeners = CopyOnWriteArrayList<Runnable>()

    var isRecording = false
        set(value) {
            field = value
            listeners.forEach(Runnable::run)
        }

    fun markUserApprovalForScreenRecording() {
        hasUserApprovedScreenRecording = true
    }

    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    companion object {
        private const val KEY_TAKE_BUG_REPORT = "key_takeBugReport"
        private const val HAS_APPROVED_SCREEN_RECORDING = "HasApprovedScreenRecord"
        private const val KEY_RECORD_SCREEN = "key_recordScreen"
        private const val KEY_TAG_TITLES = "key_tagTitles"
        const val KEY_ISSUE_TYPE_INDEX = "key_issueTypeIndex"
        const val ISSUE_TYPE_NOT_SET = -1
        const val TAG_TITLE_DELIMITER = ": "

        val ALL_ISSUE_TYPES: LinkedHashMap<Int, TraceConfig?> =
            linkedMapOf(
                Pair(R.string.performance, PresetTraceConfigs.getPerformanceConfig()),
                Pair(R.string.user_interface, PresetTraceConfigs.getUiConfig()),
                Pair(R.string.battery, PresetTraceConfigs.getBatteryConfig()),
                Pair(R.string.thermal, PresetTraceConfigs.getThermalConfig()),
                Pair(R.string.custom, null), // Null means we are using a custom trace config
            )
    }
}

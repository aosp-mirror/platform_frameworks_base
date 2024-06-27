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
import com.android.traceur.TraceUtils.PresetTraceType
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

    var takeBugreport
        get() = prefs.getBoolean(KEY_TAKE_BUG_REPORT, false)
        set(value) = prefs.edit().putBoolean(KEY_TAKE_BUG_REPORT, value).apply()

    var recordScreen
        get() = prefs.getBoolean(KEY_RECORD_SCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_RECORD_SCREEN, value).apply()

    var hasUserApprovedScreenRecording
        get() = prefs.getBoolean(HAS_APPROVED_SCREEN_RECORDING, false)
        private set(value) = prefs.edit().putBoolean(HAS_APPROVED_SCREEN_RECORDING, value).apply()

    var issueTypeRes
        get() = prefs.getInt(KEY_ISSUE_TYPE_RES, ISSUE_TYPE_NOT_SET)
        set(value) = prefs.edit().putInt(KEY_ISSUE_TYPE_RES, value).apply()

    val traceType: PresetTraceType
        get() = ALL_ISSUE_TYPES[issueTypeRes] ?: PresetTraceType.UNSET

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
        const val KEY_ISSUE_TYPE_RES = "key_issueTypeRes"
        const val ISSUE_TYPE_NOT_SET = -1

        val ALL_ISSUE_TYPES: Map<Int, PresetTraceType> =
            hashMapOf(
                Pair(R.string.performance, PresetTraceType.PERFORMANCE),
                Pair(R.string.user_interface, PresetTraceType.UI),
                Pair(R.string.battery, PresetTraceType.BATTERY),
                Pair(R.string.thermal, PresetTraceType.THERMAL)
            )
    }
}

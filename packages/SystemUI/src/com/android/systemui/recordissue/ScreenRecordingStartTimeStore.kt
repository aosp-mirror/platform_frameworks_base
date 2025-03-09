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
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.settings.UserTracker
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import org.json.JSONObject

private const val TAG = "ScreenRecordingStartTimeStore"
@VisibleForTesting const val REAL_TO_ELAPSED_TIME_OFFSET_NANOS_KEY = "realToElapsedTimeOffsetNanos"
@VisibleForTesting const val ELAPSED_REAL_TIME_NANOS_KEY = "elapsedRealTimeNanos"
private const val RECORDING_METADATA_FILE_SUFFIX = "screen_recording_metadata.json"
private const val AUTHORITY = "com.android.systemui.fileprovider"

@SysUISingleton
class ScreenRecordingStartTimeStore @Inject constructor(private val userTracker: UserTracker) {
    @VisibleForTesting val userIdToScreenRecordingStartTime = SparseArray<JSONObject>()

    fun markStartTime() {
        val elapsedRealTimeNano = SystemClock.elapsedRealtimeNanos()
        val realToElapsedTimeOffsetNano =
            TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()) -
                SystemClock.elapsedRealtimeNanos()
        val startTimeMetadata =
            JSONObject()
                .put(ELAPSED_REAL_TIME_NANOS_KEY, elapsedRealTimeNano)
                .put(REAL_TO_ELAPSED_TIME_OFFSET_NANOS_KEY, realToElapsedTimeOffsetNano)
        userIdToScreenRecordingStartTime.put(userTracker.userId, startTimeMetadata)
    }

    /**
     * Outputs start time metadata as Json to a file that can then be shared. Returns the Uri or
     * null if the file system is not usable and the start time meta data is available. Uses
     * com.android.systemui.fileprovider's authority.
     *
     * Because this file is not uniquely named, it doesn't need to be cleaned up. Every time it is
     * outputted, it will overwrite the last file's contents. This is a feature, not a bug.
     */
    fun getFileUri(context: Context): Uri? {
        val dir = context.externalCacheDir?.apply { mkdirs() } ?: return null
        try {
            val outFile =
                File(dir, RECORDING_METADATA_FILE_SUFFIX).apply {
                    userIdToScreenRecordingStartTime.get(userTracker.userId)?.let {
                        writeText(it.toString())
                    } ?: return null
                }
            return FileProvider.getUriForFile(context, AUTHORITY, outFile)
        } catch (e: Exception) {
            Log.e(TAG, "failed to get screen recording start time metadata via file uri", e)
            return null
        }
    }
}

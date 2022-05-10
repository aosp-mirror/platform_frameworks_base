/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.notification

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.util.Compile
import javax.inject.Inject

class NotifPipelineFlags @Inject constructor(
    val context: Context,
    val featureFlags: FeatureFlags
) {
    fun checkLegacyPipelineEnabled(): Boolean {
        if (!isNewPipelineEnabled()) {
            return true
        }

        if (Compile.IS_DEBUG) {
            Toast.makeText(context, "Old pipeline code running!", Toast.LENGTH_SHORT).show()
        }
        if (featureFlags.isEnabled(Flags.NEW_PIPELINE_CRASH_ON_CALL_TO_OLD_PIPELINE)) {
            throw RuntimeException("Old pipeline code running with new pipeline enabled")
        } else {
            Log.d("NotifPipeline", "Old pipeline code running with new pipeline enabled",
                    Exception())
        }
        return false
    }

    fun assertLegacyPipelineEnabled(): Unit =
        check(!isNewPipelineEnabled()) { "Old pipeline code running w/ new pipeline enabled" }

    fun isNewPipelineEnabled(): Boolean =
        featureFlags.isEnabled(Flags.NEW_NOTIFICATION_PIPELINE_RENDERING)

    fun isDevLoggingEnabled(): Boolean =
        featureFlags.isEnabled(Flags.NOTIFICATION_PIPELINE_DEVELOPER_LOGGING)

    fun isSmartspaceDedupingEnabled(): Boolean =
            featureFlags.isEnabled(Flags.SMARTSPACE) &&
                    featureFlags.isEnabled(Flags.SMARTSPACE_DEDUPING)
}
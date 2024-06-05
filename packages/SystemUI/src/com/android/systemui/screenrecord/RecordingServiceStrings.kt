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

package com.android.systemui.screenrecord

import android.content.res.Resources
import com.android.systemui.res.R

open class RecordingServiceStrings(private val res: Resources) {
    open val title
        get() = res.getString(R.string.screenrecord_title)
    open val notificationChannelDescription
        get() = res.getString(R.string.screenrecord_channel_description)
    open val startErrorResId
        get() = R.string.screenrecord_start_error
    open val startError
        get() = res.getString(R.string.screenrecord_start_error)
    open val saveErrorResId
        get() = R.string.screenrecord_save_error
    open val saveError
        get() = res.getString(R.string.screenrecord_save_error)
    open val ongoingRecording
        get() = res.getString(R.string.screenrecord_ongoing_screen_only)
    open val backgroundProcessingLabel
        get() = res.getString(R.string.screenrecord_background_processing_label)
    open val saveTitle
        get() = res.getString(R.string.screenrecord_save_title)

    val saveText
        get() = res.getString(R.string.screenrecord_save_text)
    val ongoingRecordingWithAudio
        get() = res.getString(R.string.screenrecord_ongoing_screen_and_audio)
    val stopLabel
        get() = res.getString(R.string.screenrecord_stop_label)
    val shareLabel
        get() = res.getString(R.string.screenrecord_share_label)
}

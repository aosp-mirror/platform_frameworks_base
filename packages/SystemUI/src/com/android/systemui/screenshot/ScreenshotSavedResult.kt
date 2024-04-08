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

package com.android.systemui.screenshot

import android.net.Uri
import android.os.UserHandle
import java.text.DateFormat
import java.util.Date

/**
 * Represents a saved screenshot, with the uri and user it was saved to as well as the time it was
 * saved.
 */
data class ScreenshotSavedResult(val uri: Uri, val user: UserHandle, val imageTime: Long) {
    val subject: String

    init {
        val subjectDate = DateFormat.getDateTimeInstance().format(Date(imageTime))
        subject = String.format(SCREENSHOT_SHARE_SUBJECT_TEMPLATE, subjectDate)
    }

    companion object {
        private const val SCREENSHOT_SHARE_SUBJECT_TEMPLATE = "Screenshot (%s)"
    }
}

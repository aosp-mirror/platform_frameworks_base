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

import android.os.Bundle
import android.util.Log

// Defines the category of the log, for quick filter
enum class LogCategory {
    // The default category, for logs from Pages & their Models.
    DEFAULT,

    // For logs from Spa Framework, such as BrowseActivity, EntryProvider
    FRAMEWORK,

    // For logs from Spa UI components, such as Widgets, Scaffold
    VIEW,
}

// Defines the log events in Spa.
enum class LogEvent {
    // Page related events.
    PAGE_ENTER,
    PAGE_LEAVE,

    // Entry related events.
    ENTRY_CLICK,
    ENTRY_SWITCH,
}

internal const val LOG_DATA_DISPLAY_NAME = "name"
internal const val LOG_DATA_SESSION_NAME = "session"
internal const val LOG_DATA_SWITCH_STATUS = "switch"

/**
 * The interface of logger in Spa
 */
interface SpaLogger {
    // log a message, usually for debug purpose.
    fun message(tag: String, msg: String, category: LogCategory = LogCategory.DEFAULT) {}

    // log a user event.
    fun event(
        id: String,
        event: LogEvent,
        category: LogCategory = LogCategory.DEFAULT,
        extraData: Bundle = Bundle.EMPTY
    ) {
    }
}

class LocalLogger : SpaLogger {
    override fun message(tag: String, msg: String, category: LogCategory) {
        Log.d("SpaMsg-$category", "[$tag] $msg")
    }

    override fun event(id: String, event: LogEvent, category: LogCategory, extraData: Bundle) {
        val extraMsg = extraData.toString().removeRange(0, 6)
        Log.d("SpaEvent-$category", "[$id] $event $extraMsg")
    }
}
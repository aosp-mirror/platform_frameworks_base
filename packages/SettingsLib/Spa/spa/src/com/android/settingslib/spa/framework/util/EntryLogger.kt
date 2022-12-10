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

package com.android.settingslib.spa.framework.util

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.core.os.bundleOf
import com.android.settingslib.spa.framework.common.LOG_DATA_SWITCH_STATUS
import com.android.settingslib.spa.framework.common.LocalEntryDataProvider
import com.android.settingslib.spa.framework.common.LogCategory
import com.android.settingslib.spa.framework.common.LogEvent
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory

@Composable
fun logEntryEvent(): (event: LogEvent, extraData: Bundle) -> Unit {
    val entryId = LocalEntryDataProvider.current.entryId ?: return { _, _ -> }
    val arguments = LocalEntryDataProvider.current.arguments
    return { event, extraData ->
        SpaEnvironmentFactory.instance.logger.event(
            entryId, event, category = LogCategory.VIEW, extraData = extraData.apply {
                if (arguments != null) putAll(arguments)
            }
        )
    }
}

@Composable
fun wrapOnClickWithLog(onClick: (() -> Unit)?): (() -> Unit)? {
    if (onClick == null) return null
    val logEvent = logEntryEvent()
    return {
        logEvent(LogEvent.ENTRY_CLICK, bundleOf())
        onClick()
    }
}

@Composable
fun wrapOnSwitchWithLog(onSwitch: ((checked: Boolean) -> Unit)?): ((checked: Boolean) -> Unit)? {
    if (onSwitch == null) return null
    val logEvent = logEntryEvent()
    return {
        logEvent(LogEvent.ENTRY_SWITCH, bundleOf(LOG_DATA_SWITCH_STATUS to it))
        onSwitch(it)
    }
}

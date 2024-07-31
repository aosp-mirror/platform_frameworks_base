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

import android.content.ComponentName
import android.content.Intent
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory

const val SESSION_UNKNOWN = "unknown"
const val SESSION_BROWSE = "browse"
const val SESSION_SEARCH = "search"
const val SESSION_EXTERNAL = "external"

const val KEY_DESTINATION = "spaActivityDestination"
const val KEY_HIGHLIGHT_ENTRY = "highlightEntry"
const val KEY_SESSION_SOURCE_NAME = "sessionSource"

val SPA_INTENT_RESERVED_KEYS = listOf(
    KEY_DESTINATION,
    KEY_HIGHLIGHT_ENTRY,
    KEY_SESSION_SOURCE_NAME
)

private fun createBaseIntent(): Intent? {
    val context = SpaEnvironmentFactory.instance.appContext
    val browseActivityClass = SpaEnvironmentFactory.instance.browseActivityClass ?: return null
    return Intent().setComponent(ComponentName(context, browseActivityClass))
}

fun SettingsPage.createIntent(sessionName: String? = null): Intent? {
    if (!isBrowsable()) return null
    return createBaseIntent()?.appendSpaParams(
        destination = buildRoute(),
        sessionName = sessionName
    )
}

fun SettingsEntry.createIntent(sessionName: String? = null): Intent? {
    val sp = containerPage()
    if (!sp.isBrowsable()) return null
    return createBaseIntent()?.appendSpaParams(
        destination = sp.buildRoute(),
        entryId = id,
        sessionName = sessionName
    )
}

fun Intent.appendSpaParams(
    destination: String? = null,
    entryId: String? = null,
    sessionName: String? = null
): Intent {
    return apply {
        if (destination != null) putExtra(KEY_DESTINATION, destination)
        if (entryId != null) putExtra(KEY_HIGHLIGHT_ENTRY, entryId)
        if (sessionName != null) putExtra(KEY_SESSION_SOURCE_NAME, sessionName)
    }
}

fun Intent.getDestination(): String? {
    return getStringExtra(KEY_DESTINATION)
}

fun Intent.getEntryId(): String? {
    return getStringExtra(KEY_HIGHLIGHT_ENTRY)
}

fun Intent.getSessionName(): String? {
    return getStringExtra(KEY_SESSION_SOURCE_NAME)
}

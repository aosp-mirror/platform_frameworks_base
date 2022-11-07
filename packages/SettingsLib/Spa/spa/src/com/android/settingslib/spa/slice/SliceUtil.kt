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

package com.android.settingslib.spa.slice

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.android.settingslib.spa.framework.BrowseActivity.Companion.KEY_DESTINATION
import com.android.settingslib.spa.framework.BrowseActivity.Companion.KEY_HIGHLIGHT_ENTRY
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory

// Defines SliceUri, which contains special query parameters:
//  -- KEY_DESTINATION: The route that this slice is navigated to.
//  -- KEY_HIGHLIGHT_ENTRY: The entry id of this slice
//  Other parameters can considered as runtime parameters.
// Use {entryId, runtimeParams} as the unique Id of this Slice.
typealias SliceUri = Uri

val RESERVED_KEYS = listOf(
    KEY_DESTINATION,
    KEY_HIGHLIGHT_ENTRY
)

fun SliceUri.getEntryId(): String? {
    return getQueryParameter(KEY_HIGHLIGHT_ENTRY)
}

fun SliceUri.getDestination(): String? {
    return getQueryParameter(KEY_DESTINATION)
}

fun SliceUri.getRuntimeArguments(): Bundle {
    val params = Bundle()
    for (queryName in queryParameterNames) {
        if (RESERVED_KEYS.contains(queryName)) continue
        params.putString(queryName, getQueryParameter(queryName))
    }
    return params
}

fun SliceUri.getSliceId(): String? {
    val entryId = getEntryId() ?: return null
    val params = getRuntimeArguments()
    return "${entryId}_$params"
}

fun Uri.Builder.appendSliceParams(
    route: String? = null,
    entryId: String? = null,
    runtimeArguments: Bundle? = null
): Uri.Builder {
    if (route != null) appendQueryParameter(KEY_DESTINATION, route)
    if (entryId != null) appendQueryParameter(KEY_HIGHLIGHT_ENTRY, entryId)
    if (runtimeArguments != null) {
        for (key in runtimeArguments.keySet()) {
            appendQueryParameter(key, runtimeArguments.getString(key, ""))
        }
    }
    return this
}

fun SliceUri.createBroadcastPendingIntent(): PendingIntent? {
    val context = SpaEnvironmentFactory.instance.appContext
    val sliceBroadcastClass =
        SpaEnvironmentFactory.instance.sliceBroadcastReceiverClass ?: return null
    val entryId = getEntryId() ?: return null
    return createBroadcastPendingIntent(context, sliceBroadcastClass, entryId)
}

fun SliceUri.createBrowsePendingIntent(): PendingIntent? {
    val context = SpaEnvironmentFactory.instance.appContext
    val browseActivityClass = SpaEnvironmentFactory.instance.browseActivityClass ?: return null
    val destination = getDestination() ?: return null
    val entryId = getEntryId()
    return createBrowsePendingIntent(context, browseActivityClass, destination, entryId)
}

fun Intent.createBrowsePendingIntent(): PendingIntent? {
    val context = SpaEnvironmentFactory.instance.appContext
    val browseActivityClass = SpaEnvironmentFactory.instance.browseActivityClass ?: return null
    val destination = getStringExtra(KEY_DESTINATION) ?: return null
    val entryId = getStringExtra(KEY_HIGHLIGHT_ENTRY)
    return createBrowsePendingIntent(context, browseActivityClass, destination, entryId)
}

private fun createBrowsePendingIntent(
    context: Context,
    browseActivityClass: Class<out Activity>,
    destination: String,
    entryId: String?
): PendingIntent {
    val intent = Intent().setComponent(ComponentName(context, browseActivityClass))
        .apply {
            // Set both extra and data (which is a Uri) in Slice Intent:
            // 1) extra is used in SPA navigation framework
            // 2) data is used in Slice framework
            putExtra(KEY_DESTINATION, destination)
            if (entryId != null) {
                putExtra(KEY_HIGHLIGHT_ENTRY, entryId)
            }
            data = Uri.Builder().appendSliceParams(destination, entryId).build()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

    return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
}

private fun createBroadcastPendingIntent(
    context: Context,
    sliceBroadcastClass: Class<out BroadcastReceiver>,
    entryId: String
): PendingIntent {
    val intent = Intent().setComponent(ComponentName(context, sliceBroadcastClass))
        .apply { data = Uri.Builder().appendSliceParams(entryId = entryId).build() }
    return PendingIntent.getBroadcast(
        context, 0 /* requestCode */, intent,
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
    )
}

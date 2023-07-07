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

package com.android.settingslib.spa.slice.provider

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.core.R
import androidx.core.graphics.drawable.IconCompat
import androidx.slice.Slice
import androidx.slice.SliceManager
import androidx.slice.builders.ListBuilder
import androidx.slice.builders.SliceAction
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.slice.createBroadcastPendingIntent
import com.android.settingslib.spa.slice.createBrowsePendingIntent

fun createDemoBrowseSlice(sliceUri: Uri, title: String, summary: String): Slice? {
    val intent = sliceUri.createBrowsePendingIntent() ?: return null
    return createDemoSlice(sliceUri, title, summary, intent)
}

fun createDemoActionSlice(sliceUri: Uri, title: String, summary: String): Slice? {
    val intent = sliceUri.createBroadcastPendingIntent() ?: return null
    return createDemoSlice(sliceUri, title, summary, intent)
}

fun createDemoSlice(sliceUri: Uri, title: String, summary: String, intent: PendingIntent): Slice? {
    val context = SpaEnvironmentFactory.instance.appContext
    if (!SliceManager.getInstance(context).pinnedSlices.contains(sliceUri)) return null
    return ListBuilder(context, sliceUri, ListBuilder.INFINITY)
        .addRow(ListBuilder.RowBuilder().apply {
            setPrimaryAction(createSliceAction(context, intent))
            setTitle(title)
            setSubtitle(summary)
        }).build()
}

private fun createSliceAction(context: Context, intent: PendingIntent): SliceAction {
    return SliceAction.create(
        intent,
        IconCompat.createWithResource(context, R.drawable.notification_action_background),
        ListBuilder.ICON_IMAGE,
        "Enter app"
    )
}

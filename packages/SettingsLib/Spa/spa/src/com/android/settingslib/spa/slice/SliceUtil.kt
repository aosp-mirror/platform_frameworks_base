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

import android.net.Uri
import android.os.Bundle
import com.android.settingslib.spa.framework.util.KEY_DESTINATION
import com.android.settingslib.spa.framework.util.KEY_HIGHLIGHT_ENTRY

// Defines SliceUri, which contains special query parameters:
//  -- KEY_DESTINATION: The route that this slice is navigated to.
//  -- KEY_HIGHLIGHT_ENTRY: The entry id of this slice
//  Other parameters can considered as runtime parameters.
// Use {entryId, runtimeParams} as the unique Id of this Slice.
typealias SliceUri = Uri

fun SliceUri.getEntryId(): String? {
    return getQueryParameter(KEY_HIGHLIGHT_ENTRY)
}

fun Uri.Builder.appendSpaParams(
    destination: String? = null,
    entryId: String? = null,
    runtimeArguments: Bundle? = null
): Uri.Builder {
    if (destination != null) appendQueryParameter(KEY_DESTINATION, destination)
    if (entryId != null) appendQueryParameter(KEY_HIGHLIGHT_ENTRY, entryId)
    if (runtimeArguments != null) {
        for (key in runtimeArguments.keySet()) {
            appendQueryParameter(key, runtimeArguments.getString(key, ""))
        }
    }
    return this
}


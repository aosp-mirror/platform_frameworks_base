/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.controls.ui

import android.content.ContentProvider
import android.graphics.drawable.Icon

class CanUseIconPredicate(private val currentUserId: Int) : (Icon) -> Boolean {

    override fun invoke(icon: Icon): Boolean =
        if (icon.type == Icon.TYPE_URI || icon.type == Icon.TYPE_URI_ADAPTIVE_BITMAP) {
            ContentProvider.getUserIdFromUri(icon.uri, currentUserId) == currentUserId
        } else {
            true
        }
}

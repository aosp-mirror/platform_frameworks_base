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

package com.android.credentialmanager.model.get

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Drawable
import com.android.credentialmanager.model.EntryInfo

class AuthenticationEntryInfo(
    providerId: String,
    entryKey: String,
    entrySubkey: String,
    pendingIntent: PendingIntent?,
    fillInIntent: Intent?,
    val title: String,
    val providerDisplayName: String,
    val icon: Drawable,
    // The entry had been unlocked and turned out to be empty. Used to determine whether to
    // show "Tap to unlock" or "No sign-in info" for this entry.
    val isUnlockedAndEmpty: Boolean,
    // True if the entry was the last one unlocked. Used to show the no sign-in info snackbar.
    val isLastUnlocked: Boolean,
) : EntryInfo(
    providerId,
    entryKey, entrySubkey,
    pendingIntent,
    fillInIntent,
    shouldTerminateUiUponSuccessfulProviderResult = false,
)
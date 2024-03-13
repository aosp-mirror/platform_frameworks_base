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
import com.android.credentialmanager.model.BiometricRequestInfo
import com.android.credentialmanager.model.CredentialType
import com.android.credentialmanager.model.EntryInfo
import java.time.Instant

class CredentialEntryInfo(
    providerId: String,
    entryKey: String,
    entrySubkey: String,
    pendingIntent: PendingIntent?,
    fillInIntent: Intent?,
    /** Type of this credential used for sorting. Not localized so must not be directly displayed. */
    val credentialType: CredentialType,
    /**
     * String type value of this credential used for sorting. Not localized so must not be directly
     * displayed.
     */
    val rawCredentialType: String,
    /** Localized type value of this credential used for display purpose. */
    val credentialTypeDisplayName: String,
    val providerDisplayName: String,
    val userName: String,
    val displayName: String?,
    val icon: Drawable?,
    val shouldTintIcon: Boolean,
    val lastUsedTimeMillis: Instant?,
    val isAutoSelectable: Boolean,
    val entryGroupId: String, // Used for deduplication, and displayed as the grouping title
                              // "For <value-of-entryGroupId>" on the more-option screen.
    val isDefaultIconPreferredAsSingleProvider: Boolean,
    val affiliatedDomain: String?,
    val biometricRequest: BiometricRequestInfo? = null,
) : EntryInfo(
    providerId,
    entryKey,
    entrySubkey,
    pendingIntent,
    fillInIntent,
    shouldTerminateUiUponSuccessfulProviderResult = true,
)
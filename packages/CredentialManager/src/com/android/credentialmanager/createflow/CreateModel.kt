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

package com.android.credentialmanager.createflow

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Drawable

open class ProviderInfo(
  val icon: Drawable,
  val id: String,
  val displayName: String,
)

class EnabledProviderInfo(
  icon: Drawable,
  name: String,
  displayName: String,
  var createOptions: List<CreateOptionInfo>,
  var remoteEntry: RemoteInfo?,
) : ProviderInfo(icon, name, displayName)

class DisabledProviderInfo(
  icon: Drawable,
  name: String,
  displayName: String,
) : ProviderInfo(icon, name, displayName)

open class EntryInfo (
  val providerId: String,
  val entryKey: String,
  val entrySubkey: String,
  val pendingIntent: PendingIntent?,
  val fillInIntent: Intent?,
)

class CreateOptionInfo(
  providerId: String,
  entryKey: String,
  entrySubkey: String,
  pendingIntent: PendingIntent?,
  fillInIntent: Intent?,
  val userProviderDisplayName: String?,
  val profileIcon: Drawable?,
  val passwordCount: Int?,
  val passkeyCount: Int?,
  val totalCredentialCount: Int?,
  val lastUsedTimeMillis: Long?,
) : EntryInfo(providerId, entryKey, entrySubkey, pendingIntent, fillInIntent)

class RemoteInfo(
  providerId: String,
  entryKey: String,
  entrySubkey: String,
  pendingIntent: PendingIntent?,
  fillInIntent: Intent?,
) : EntryInfo(providerId, entryKey, entrySubkey, pendingIntent, fillInIntent)

data class RequestDisplayInfo(
  val title: String,
  val subtitle: String?,
  val type: String,
  val appDomainName: String,
  val typeIcon: Drawable,
)

/**
 * This is initialized to be the most recent used. Can then be changed if
 * user selects a different entry on the more option page.
 */
data class ActiveEntry (
  val activeProvider: EnabledProviderInfo,
  val activeEntryInfo: EntryInfo,
)

/** The name of the current screen. */
enum class CreateScreenState {
  PASSKEY_INTRO,
  PROVIDER_SELECTION,
  CREATION_OPTION_SELECTION,
  MORE_OPTIONS_SELECTION,
  MORE_OPTIONS_ROW_INTRO,
  EXTERNAL_ONLY_SELECTION,
}

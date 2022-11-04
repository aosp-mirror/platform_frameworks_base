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

import android.graphics.drawable.Drawable

data class ProviderInfo(
  val icon: Drawable,
  val name: String,
  val displayName: String,
  val createOptions: List<CreateOptionInfo>,
  val isDefault: Boolean,
)

open class EntryInfo (
  val entryKey: String,
  val entrySubkey: String,
)

class CreateOptionInfo(
  entryKey: String,
  entrySubkey: String,
  val userProviderDisplayName: String,
  val credentialTypeIcon: Drawable,
  val profileIcon: Drawable,
  val passwordCount: Int,
  val passkeyCount: Int,
  val totalCredentialCount: Int,
  val lastUsedTimeMillis: Long?,
) : EntryInfo(entryKey, entrySubkey)

data class RequestDisplayInfo(
  val userName: String,
  val displayName: String,
  val type: String,
  val appDomainName: String,
)

/**
 * This is initialized to be the most recent used. Can then be changed if
 * user selects a different entry on the more option page.
 */
data class ActiveEntry (
  val activeProvider: ProviderInfo,
  val activeEntryInfo: EntryInfo,
)

/** The name of the current screen. */
enum class CreateScreenState {
  PASSKEY_INTRO,
  PROVIDER_SELECTION,
  CREATION_OPTION_SELECTION,
  MORE_OPTIONS_SELECTION,
  MORE_OPTIONS_ROW_INTRO,
}

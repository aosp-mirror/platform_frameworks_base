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

package com.android.credentialmanager.getflow

import android.graphics.drawable.Drawable

data class ProviderInfo(
  val icon: Drawable,
  val name: String,
  val displayName: String,
  val credentialTypeIcon: Drawable,
  val credentialOptions: List<CredentialOptionInfo>,
  // TODO: Add the authenticationOption
)

open class EntryInfo (
  val entryKey: String,
  val entrySubkey: String,
)

class CredentialOptionInfo(
  entryKey: String,
  entrySubkey: String,
  val icon: Drawable,
  val usageData: String,
) : EntryInfo(entryKey, entrySubkey)

data class RequestDisplayInfo(
  val userName: String,
  val displayName: String,
  val type: String,
  val appDomainName: String,
)

/** The name of the current screen. */
enum class GetScreenState {
  CREDENTIAL_SELECTION,
}

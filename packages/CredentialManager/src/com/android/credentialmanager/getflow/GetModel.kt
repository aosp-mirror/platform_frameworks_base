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

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Drawable

data class ProviderInfo(
  /**
   * Unique id (component name) of this provider.
   * Not for display purpose - [displayName] should be used for ui rendering.
   */
  val id: String,
  val icon: Drawable,
  val displayName: String,
  val credentialEntryList: List<CredentialEntryInfo>,
  val authenticationEntry: AuthenticationEntryInfo?,
  val remoteEntry: RemoteEntryInfo?,
  val actionEntryList: List<ActionEntryInfo>,
)

/** Display-centric data structure derived from the [ProviderInfo]. This abstraction is not grouping
 *  by the provider id but instead focuses on structures convenient for display purposes. */
data class ProviderDisplayInfo(
  /**
   * The credential entries grouped by userName, derived from all entries of the [providerInfoList].
   * Note that the list order matters to the display order.
   */
  val sortedUserNameToCredentialEntryList: List<PerUserNameCredentialEntryList>,
  val authenticationEntryList: List<AuthenticationEntryInfo>,
  val remoteEntry: RemoteEntryInfo?
)

abstract class EntryInfo (
  /** Unique id combination of this entry. Not for display purpose. */
  val providerId: String,
  val entryKey: String,
  val entrySubkey: String,
  val pendingIntent: PendingIntent?,
  val fillInIntent: Intent?,
)

class CredentialEntryInfo(
  providerId: String,
  entryKey: String,
  entrySubkey: String,
  pendingIntent: PendingIntent?,
  fillInIntent: Intent?,
  /** Type of this credential used for sorting. Not localized so must not be directly displayed. */
  val credentialType: String,
  /** Localized type value of this credential used for display purpose. */
  val credentialTypeDisplayName: String,
  val userName: String,
  val displayName: String?,
  val icon: Drawable?,
  val lastUsedTimeMillis: Long?,
) : EntryInfo(providerId, entryKey, entrySubkey, pendingIntent, fillInIntent)

class AuthenticationEntryInfo(
  providerId: String,
  entryKey: String,
  entrySubkey: String,
  pendingIntent: PendingIntent?,
  fillInIntent: Intent?,
  val title: String,
  val icon: Drawable,
) : EntryInfo(providerId, entryKey, entrySubkey, pendingIntent, fillInIntent)

class RemoteEntryInfo(
  providerId: String,
  entryKey: String,
  entrySubkey: String,
  pendingIntent: PendingIntent?,
  fillInIntent: Intent?,
) : EntryInfo(providerId, entryKey, entrySubkey, pendingIntent, fillInIntent)

class ActionEntryInfo(
  providerId: String,
  entryKey: String,
  entrySubkey: String,
  pendingIntent: PendingIntent?,
  fillInIntent: Intent?,
  val title: String,
  val icon: Drawable,
  val subTitle: String?,
) : EntryInfo(providerId, entryKey, entrySubkey, pendingIntent, fillInIntent)

data class RequestDisplayInfo(
  val appName: String,
)

/**
 * @property userName the userName that groups all the entries in this list
 * @property sortedCredentialEntryList the credential entries associated with the [userName] sorted
 *                                     by last used timestamps and then by credential types
 */
data class PerUserNameCredentialEntryList(
  val userName: String,
  val sortedCredentialEntryList: List<CredentialEntryInfo>,
)

/** The name of the current screen. */
enum class GetScreenState {
  /** The primary credential selection page. */
  PRIMARY_SELECTION,
  /** The secondary credential selection page, where all sign-in options are listed. */
  ALL_SIGN_IN_OPTIONS,
  /** The snackbar only page when there's no account but only a remoteEntry. */
  REMOTE_ONLY,
}

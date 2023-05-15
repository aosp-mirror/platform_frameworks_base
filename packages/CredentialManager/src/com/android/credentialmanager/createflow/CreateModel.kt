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
import com.android.credentialmanager.common.BaseEntry
import com.android.credentialmanager.common.CredentialType
import java.time.Instant

data class CreateCredentialUiState(
  val enabledProviders: List<EnabledProviderInfo>,
  val disabledProviders: List<DisabledProviderInfo>? = null,
  val currentScreenState: CreateScreenState,
  val requestDisplayInfo: RequestDisplayInfo,
  val sortedCreateOptionsPairs: List<Pair<CreateOptionInfo, EnabledProviderInfo>>,
  val activeEntry: ActiveEntry? = null,
  val remoteEntry: RemoteInfo? = null,
  val foundCandidateFromUserDefaultProvider: Boolean,
)

internal fun isFlowAutoSelectable(
    uiState: CreateCredentialUiState
): Boolean {
  return uiState.requestDisplayInfo.isAutoSelectRequest &&
      // Even if the flow is auto selectable, still allow passkey intro screen to show once if
      // applicable.
      uiState.currentScreenState != CreateScreenState.PASSKEY_INTRO &&
      uiState.currentScreenState != CreateScreenState.MORE_ABOUT_PASSKEYS_INTRO &&
      uiState.sortedCreateOptionsPairs.size == 1 &&
      uiState.activeEntry?.activeEntryInfo?.let {
        it is CreateOptionInfo && it.allowAutoSelect
      } ?: false
}

internal fun hasContentToDisplay(state: CreateCredentialUiState): Boolean {
    return state.sortedCreateOptionsPairs.isNotEmpty() ||
        (!state.requestDisplayInfo.preferImmediatelyAvailableCredentials &&
            state.remoteEntry != null)
}

open class ProviderInfo(
  val icon: Drawable,
  val id: String,
  val displayName: String,
)

class EnabledProviderInfo(
    icon: Drawable,
    id: String,
    displayName: String,
    // Sorted by last used time
    var sortedCreateOptions: List<CreateOptionInfo>,
    var remoteEntry: RemoteInfo?,
) : ProviderInfo(icon, id, displayName)

class DisabledProviderInfo(
  icon: Drawable,
  id: String,
  displayName: String,
) : ProviderInfo(icon, id, displayName)

class CreateOptionInfo(
    providerId: String,
    entryKey: String,
    entrySubkey: String,
    pendingIntent: PendingIntent?,
    fillInIntent: Intent?,
    val userProviderDisplayName: String,
    val profileIcon: Drawable?,
    val passwordCount: Int?,
    val passkeyCount: Int?,
    val totalCredentialCount: Int?,
    val lastUsedTime: Instant,
    val footerDescription: String?,
    val allowAutoSelect: Boolean,
) : BaseEntry(
    providerId,
    entryKey,
    entrySubkey,
    pendingIntent,
    fillInIntent,
    shouldTerminateUiUponSuccessfulProviderResult = true,
)

class RemoteInfo(
  providerId: String,
  entryKey: String,
  entrySubkey: String,
  pendingIntent: PendingIntent?,
  fillInIntent: Intent?,
) : BaseEntry(
    providerId,
    entryKey,
    entrySubkey,
    pendingIntent,
    fillInIntent,
    shouldTerminateUiUponSuccessfulProviderResult = true,
)

data class RequestDisplayInfo(
  val title: String,
  val subtitle: String?,
  val type: CredentialType,
  val appName: String,
  val typeIcon: Drawable,
  val preferImmediatelyAvailableCredentials: Boolean,
  val appPreferredDefaultProviderId: String?,
  val userSetDefaultProviderIds: Set<String>,
  // Whether the given CreateCredentialRequest allows auto select.
  val isAutoSelectRequest: Boolean,
)

/**
 * This is initialized to be the most recent used. Can then be changed if
 * user selects a different entry on the more option page.
 */
data class ActiveEntry (
  val activeProvider: EnabledProviderInfo,
  val activeEntryInfo: BaseEntry,
)

/** The name of the current screen. */
enum class CreateScreenState {
  PASSKEY_INTRO,
  MORE_ABOUT_PASSKEYS_INTRO,
  CREATION_OPTION_SELECTION,
  MORE_OPTIONS_SELECTION,
  DEFAULT_PROVIDER_CONFIRMATION,
  EXTERNAL_ONLY_SELECTION,
}

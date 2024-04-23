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

import android.credentials.flags.Flags.credmanBiometricApiEnabled
import android.graphics.drawable.Drawable
import com.android.credentialmanager.R
import com.android.credentialmanager.model.CredentialType
import com.android.credentialmanager.model.EntryInfo
import com.android.credentialmanager.model.creation.CreateOptionInfo
import com.android.credentialmanager.model.creation.RemoteInfo

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

/**
 * Checks if this create flow is a biometric flow. Note that this flow differs slightly from the
 * autoselect 'get' flow. Namely, given there can be multiple providers, rather than multiple
 * accounts, the idea is that autoselect is ever only enabled for a single provider (or even, in
 * that case, a single 'type' (family only, or work only) for a provider). However, for all other
 * cases, the biometric screen should always show up if that entry contains the biometric bit.
 */
internal fun findBiometricFlowEntry(
    activeEntry: ActiveEntry,
    isAutoSelectFlow: Boolean,
): CreateOptionInfo? {
    if (!credmanBiometricApiEnabled()) {
        return null
    }
    if (isAutoSelectFlow) {
        // Since this is the create flow, auto select will only ever be true for a single provider.
        // However, for all other cases, biometric should be used if that bit is opted into. If
        // they clash, autoselect is always preferred, but that's only if there's a single provider.
        return null
    }
    val biometricEntry = getCreateEntry(activeEntry)
    return if (biometricEntry?.biometricRequest != null) biometricEntry else null
}

/**
 * Retrieves the activeEntry by validating it is a [CreateOptionInfo]. This is done by ensuring
 * that the [activeEntry] exists as a [CreateOptionInfo] to retrieve its [EntryInfo].
 */
internal fun getCreateEntry(
    activeEntry: ActiveEntry?,
): CreateOptionInfo? {
    val entry = activeEntry?.activeEntryInfo
    if (entry !is CreateOptionInfo) {
        return null
    }
    return entry
}

/**
* Determines if the flow is a biometric flow by taking into account autoselect criteria.
*/
internal fun isBiometricFlow(
    activeEntry: ActiveEntry,
    isAutoSelectFlow: Boolean,
) = findBiometricFlowEntry(activeEntry, isAutoSelectFlow) != null

/**
 * This utility presents the correct resource string for the create flows title conditionally.
 * Similar to generateDisplayTitleTextResCode in the 'get' flow, but for the create flow instead.
 * This is for the title, and is a shared resource, unlike the specific unlock request text.
 * E.g. this will look something like: "Create passkey to sign in to Tribank."
 * // TODO(b/330396140) : Validate approach and add dynamic auth strings
 */
internal fun getCreateTitleResCode(createRequestDisplayInfo: RequestDisplayInfo): Int =
    when (createRequestDisplayInfo.type) {
        CredentialType.PASSKEY ->
            R.string.choose_create_option_passkey_title

        CredentialType.PASSWORD ->
            R.string.choose_create_option_password_title

        CredentialType.UNKNOWN ->
            R.string.choose_create_option_sign_in_title
    }

internal fun isFlowAutoSelectable(
    uiState: CreateCredentialUiState
): Boolean {
    return isFlowAutoSelectable(uiState.requestDisplayInfo, uiState.activeEntry,
        uiState.sortedCreateOptionsPairs)
}

/**
 * When initializing, the [CreateCredentialUiState] is generated after the initial screen is set.
 * This overloaded method allows identifying if the flow is auto selectable prior to the creation
 * of the [CreateCredentialUiState].
 */
internal fun isFlowAutoSelectable(
    requestDisplayInfo: RequestDisplayInfo,
    activeEntry: ActiveEntry?,
    sortedCreateOptionsPairs: List<Pair<CreateOptionInfo, EnabledProviderInfo>>
): Boolean {
    val isAutoSelectRequest = requestDisplayInfo.isAutoSelectRequest
    if (sortedCreateOptionsPairs.size != 1) {
        return false
    }
    val singleEntry = getCreateEntry(activeEntry)
    return isAutoSelectRequest && singleEntry?.allowAutoSelect == true
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
    val activeEntryInfo: EntryInfo,
)

/** The name of the current screen. */
enum class CreateScreenState {
  CREATION_OPTION_SELECTION,
  BIOMETRIC_SELECTION,
  MORE_OPTIONS_SELECTION,
  DEFAULT_PROVIDER_CONFIRMATION,
  EXTERNAL_ONLY_SELECTION,
  MORE_OPTIONS_SELECTION_ONLY,
}

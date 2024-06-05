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

import android.credentials.flags.Flags.selectorUiImprovementsEnabled
import android.credentials.flags.Flags.credmanBiometricApiEnabled
import android.graphics.drawable.Drawable
import androidx.credentials.PriorityHints
import com.android.credentialmanager.R
import com.android.credentialmanager.model.CredentialType
import com.android.credentialmanager.model.get.ProviderInfo
import com.android.credentialmanager.model.EntryInfo
import com.android.credentialmanager.model.get.AuthenticationEntryInfo
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.model.get.RemoteEntryInfo
import com.android.internal.util.Preconditions
import java.time.Instant

data class GetCredentialUiState(
    val isRequestForAllOptions: Boolean,
    val providerInfoList: List<ProviderInfo>,
    val requestDisplayInfo: RequestDisplayInfo,
    val providerDisplayInfo: ProviderDisplayInfo =
            toProviderDisplayInfo(providerInfoList, requestDisplayInfo.typePriorityMap),
    val currentScreenState: GetScreenState = toGetScreenState(
            providerDisplayInfo, isRequestForAllOptions),
    val activeEntry: EntryInfo? = toActiveEntry(providerDisplayInfo),
    val isNoAccount: Boolean = false,
)

/**
 * Checks if this get flow is a biometric selection flow by ensuring that the first account has a
 * single credential entry to display. The presently agreed upon condition validates this flow for
 * a single account. In the case when there's a single credential, this flow matches the auto
 * select criteria, but with the possibility that the two flows (autoselect and biometric) may
 * collide. In those collision cases, the auto select flow is supported over the biometric flow.
 * If there is a single account but more than one credential, and the first ranked credential has
 * the biometric bit flipped on, we will use the biometric flow. If all conditions are valid, this
 * responds with the entry utilized by the biometricFlow, or null otherwise.
 */
internal fun findBiometricFlowEntry(
    providerDisplayInfo: ProviderDisplayInfo,
    isAutoSelectFlow: Boolean
): CredentialEntryInfo? {
    if (!credmanBiometricApiEnabled()) {
        return null
    }
    if (isAutoSelectFlow) {
        // For this to be true, it must be the case that there is a single entry and a single
        // account. If that is the case, and auto-select is enabled along side the one-tap flow, we
        // always favor that over the one tap flow.
        return null
    }
    // The flow through an authentication entry, even if only a singular entry exists, is deemed
    // as not being eligible for the single tap flow given that it adds any number of credentials
    // once unlocked; essentially, this entry contains additional complexities behind it, making it
    // invalid.
    if (providerDisplayInfo.authenticationEntryList.isNotEmpty()) {
        return null
    }
    val singleAccountEntryList = getCredentialEntryListIffSingleAccount(
        providerDisplayInfo.sortedUserNameToCredentialEntryList) ?: return null

    val firstEntry = singleAccountEntryList.firstOrNull()
    return if (firstEntry?.biometricRequest != null) firstEntry else null
}

/**
 * A utility method that will procure the credential entry list if and only if the credential entry
 * list is for a singular account use case. This can be used for various flows that condition on
 * a singular account.
 */
internal fun getCredentialEntryListIffSingleAccount(
    sortedUserNameToCredentialEntryList: List<PerUserNameCredentialEntryList>
): List<CredentialEntryInfo>? {
    if (sortedUserNameToCredentialEntryList.size != 1) {
        return null
    }
    val entryList = sortedUserNameToCredentialEntryList.firstOrNull() ?: return null
    val sortedEntryList = entryList.sortedCredentialEntryList
    return sortedEntryList
}

internal fun hasContentToDisplay(state: GetCredentialUiState): Boolean {
    return state.providerDisplayInfo.sortedUserNameToCredentialEntryList.isNotEmpty() ||
        state.providerDisplayInfo.authenticationEntryList.isNotEmpty() ||
        (state.providerDisplayInfo.remoteEntry != null &&
            !state.requestDisplayInfo.preferImmediatelyAvailableCredentials)
}

internal fun findAutoSelectEntry(providerDisplayInfo: ProviderDisplayInfo): CredentialEntryInfo? {
    if (providerDisplayInfo.authenticationEntryList.isNotEmpty()) {
        return null
    }
    val entryList = getCredentialEntryListIffSingleAccount(
        providerDisplayInfo.sortedUserNameToCredentialEntryList) ?: return null
    if (entryList.size != 1) {
        return null
    }
    val entry = entryList.firstOrNull() ?: return null
    if (entry.isAutoSelectable) {
        return entry
    }
    return null
}

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

data class RequestDisplayInfo(
    val appName: String,
    val preferImmediatelyAvailableCredentials: Boolean,
    val preferIdentityDocUi: Boolean,
    // A top level branding icon + display name preferred by the app.
    val preferTopBrandingContent: TopBrandingContent?,
    // Map of credential type -> priority.
    val typePriorityMap: Map<String, Int>,
)

data class TopBrandingContent(
    val icon: Drawable,
    val displayName: String,
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

    /** The single tap biometric selection page. */
    BIOMETRIC_SELECTION,

    /**
     * The secondary credential selection page, where all sign-in options are listed.
     *
     * This state is expected to go back to PRIMARY_SELECTION on back navigation
     */
    ALL_SIGN_IN_OPTIONS,

    /** The snackbar only page when there's no account but only a remoteEntry. */
    REMOTE_ONLY,

    /** The snackbar when there are only auth entries and all of them turn out to be empty. */
    UNLOCKED_AUTH_ENTRIES_ONLY,

    /**
     * The secondary credential selection page, where all sign-in options are listed.
     *
     * This state has no option for the user to navigate back to PRIMARY_SELECTION, and
     * instead can be terminated independently.
     */
    ALL_SIGN_IN_OPTIONS_ONLY,
}


/**
 * IMPORTANT: new invocation should be mindful that this method will throw if more than 1 remote
 * entry exists
 *
 * @hide
 */
fun toProviderDisplayInfo(
    providerInfoList: List<ProviderInfo>,
    typePriorityMap: Map<String, Int>,
): ProviderDisplayInfo {
    val userNameToCredentialEntryMap = mutableMapOf<String, MutableList<CredentialEntryInfo>>()
    val authenticationEntryList = mutableListOf<AuthenticationEntryInfo>()
    val remoteEntryList = mutableListOf<RemoteEntryInfo>()
    providerInfoList.forEach { providerInfo ->
        authenticationEntryList.addAll(providerInfo.authenticationEntryList)
        providerInfo.remoteEntry?.let {
            remoteEntryList.add(it)
        }
        // There can only be at most one remote entry
        Preconditions.checkState(remoteEntryList.size <= 1)

        providerInfo.credentialEntryList.forEach {
            userNameToCredentialEntryMap.compute(
                if (selectorUiImprovementsEnabled()) it.entryGroupId else it.userName
            ) { _, v ->
                if (v == null) {
                    mutableListOf(it)
                } else {
                    v.add(it)
                    v
                }
            }
        }
    }

    // Compose sortedUserNameToCredentialEntryList
    val comparator = CredentialEntryInfoComparatorByTypeThenTimestamp(typePriorityMap)
    // Sort per username
    userNameToCredentialEntryMap.values.forEach {
        it.sortWith(comparator)
    }
    // Transform to list of PerUserNameCredentialEntryLists and then sort the outer list (of
    // entries grouped by username / entryGroupId) based on the latest timestamp within that
    // PerUserNameCredentialEntryList
    val sortedUserNameToCredentialEntryList = userNameToCredentialEntryMap.map {
        PerUserNameCredentialEntryList(it.key, it.value)
    }.sortedWith(
        compareByDescending {
            it.sortedCredentialEntryList.maxByOrNull{ entry ->
                entry.lastUsedTimeMillis ?: Instant.MIN
            }?.lastUsedTimeMillis ?: Instant.MIN
        }
    )

    return ProviderDisplayInfo(
        sortedUserNameToCredentialEntryList = sortedUserNameToCredentialEntryList,
        authenticationEntryList = authenticationEntryList,
        remoteEntry = remoteEntryList.getOrNull(0),
    )
}

/**
 * This generates the res code for the large display title text for the selector. For example, it
 * retrieves the resource for strings like: "Use your saved passkey for *rpName*".
 * TODO(b/330396140) : Validate approach and add dynamic auth strings
 */
internal fun generateDisplayTitleTextResCode(
    singleEntryType: CredentialType,
    authenticationEntryList: List<AuthenticationEntryInfo> = emptyList()
): Int =
    if (singleEntryType == CredentialType.PASSKEY)
        R.string.get_dialog_title_use_passkey_for
    else if (singleEntryType == CredentialType.PASSWORD)
        R.string.get_dialog_title_use_password_for
    else if (authenticationEntryList.isNotEmpty())
        R.string.get_dialog_title_unlock_options_for
    else R.string.get_dialog_title_use_sign_in_for

fun toActiveEntry(
    providerDisplayInfo: ProviderDisplayInfo,
): EntryInfo? {
    val sortedUserNameToCredentialEntryList =
        providerDisplayInfo.sortedUserNameToCredentialEntryList
    val authenticationEntryList = providerDisplayInfo.authenticationEntryList
    var activeEntry: EntryInfo? = null
    if (sortedUserNameToCredentialEntryList
            .size == 1 && authenticationEntryList.isEmpty()
    ) {
        activeEntry = sortedUserNameToCredentialEntryList.first().sortedCredentialEntryList.first()
    } else if (
        sortedUserNameToCredentialEntryList
            .isEmpty() && authenticationEntryList.size == 1
    ) {
        activeEntry = authenticationEntryList.first()
    }
    return activeEntry
}

private fun toGetScreenState(
    providerDisplayInfo: ProviderDisplayInfo,
    isRequestForAllOptions: Boolean
): GetScreenState {
    return if (providerDisplayInfo.sortedUserNameToCredentialEntryList.isEmpty() &&
        providerDisplayInfo.remoteEntry == null &&
        providerDisplayInfo.authenticationEntryList.all { it.isUnlockedAndEmpty })
        GetScreenState.UNLOCKED_AUTH_ENTRIES_ONLY
    else if (isRequestForAllOptions)
        GetScreenState.ALL_SIGN_IN_OPTIONS_ONLY
    else if (providerDisplayInfo.sortedUserNameToCredentialEntryList.isEmpty() &&
        providerDisplayInfo.authenticationEntryList.isEmpty() &&
        providerDisplayInfo.remoteEntry != null)
        GetScreenState.REMOTE_ONLY
    else if (isBiometricFlow(providerDisplayInfo, isFlowAutoSelectable(providerDisplayInfo)))
        GetScreenState.BIOMETRIC_SELECTION
    else GetScreenState.PRIMARY_SELECTION
}

/**
 * Determines if the flow is a biometric flow by taking into account autoselect criteria.
 */
internal fun isBiometricFlow(providerDisplayInfo: ProviderDisplayInfo, isAutoSelectFlow: Boolean) =
    findBiometricFlowEntry(providerDisplayInfo, isAutoSelectFlow) != null

/**
 * Determines if the flow is an autoselect flow.
 */
internal fun isFlowAutoSelectable(providerDisplayInfo: ProviderDisplayInfo) =
    findAutoSelectEntry(providerDisplayInfo) != null

internal class CredentialEntryInfoComparatorByTypeThenTimestamp(
        val typePriorityMap: Map<String, Int>,
) : Comparator<CredentialEntryInfo> {
    override fun compare(p0: CredentialEntryInfo, p1: CredentialEntryInfo): Int {
        // First rank by priorities of each credential type.
        if (p0.rawCredentialType != p1.rawCredentialType) {
            val p0Priority = typePriorityMap.getOrDefault(
                    p0.rawCredentialType, PriorityHints.PRIORITY_DEFAULT
            )
            val p1Priority = typePriorityMap.getOrDefault(
                    p1.rawCredentialType, PriorityHints.PRIORITY_DEFAULT
            )
            if (p0Priority < p1Priority) {
                return -1
            } else if (p1Priority < p0Priority) {
                return 1
            }
        }
        // Then rank by last used timestamps.
        val p0LastUsedTimeMillis = p0.lastUsedTimeMillis
        val p1LastUsedTimeMillis = p1.lastUsedTimeMillis
        // Then order by last used timestamp
        if (p0LastUsedTimeMillis != null && p1LastUsedTimeMillis != null) {
            if (p0LastUsedTimeMillis < p1LastUsedTimeMillis) {
                return 1
            } else if (p0LastUsedTimeMillis > p1LastUsedTimeMillis) {
                return -1
            }
        } else if (p0LastUsedTimeMillis != null) {
            return -1
        } else if (p1LastUsedTimeMillis != null) {
            return 1
        }
        return 0
    }
}
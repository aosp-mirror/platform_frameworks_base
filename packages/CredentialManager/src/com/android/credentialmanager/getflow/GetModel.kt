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
import com.android.credentialmanager.model.get.ProviderInfo
import com.android.credentialmanager.model.EntryInfo
import com.android.credentialmanager.model.CredentialType
import com.android.credentialmanager.model.get.AuthenticationEntryInfo
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.model.get.RemoteEntryInfo
import com.android.internal.util.Preconditions

data class GetCredentialUiState(
    val providerInfoList: List<ProviderInfo>,
    val requestDisplayInfo: RequestDisplayInfo,
    val providerDisplayInfo: ProviderDisplayInfo = toProviderDisplayInfo(providerInfoList),
    val currentScreenState: GetScreenState = toGetScreenState(providerDisplayInfo),
    val activeEntry: EntryInfo? = toActiveEntry(providerDisplayInfo),
    val isNoAccount: Boolean = false,
)

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
    if (providerDisplayInfo.sortedUserNameToCredentialEntryList.size == 1) {
        val entryList = providerDisplayInfo.sortedUserNameToCredentialEntryList.firstOrNull()
            ?: return null
        if (entryList.sortedCredentialEntryList.size == 1) {
            val entry = entryList.sortedCredentialEntryList.firstOrNull() ?: return null
            if (entry.isAutoSelectable) {
                return entry
            }
        }
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

    /** The secondary credential selection page, where all sign-in options are listed. */
    ALL_SIGN_IN_OPTIONS,

    /** The snackbar only page when there's no account but only a remoteEntry. */
    REMOTE_ONLY,

    /** The snackbar when there are only auth entries and all of them turn out to be empty. */
    UNLOCKED_AUTH_ENTRIES_ONLY,
}


/**
 * IMPORTANT: new invocation should be mindful that this method will throw if more than 1 remote
 * entry exists
 *
 * @hide
 */
fun toProviderDisplayInfo(
    providerInfoList: List<ProviderInfo>
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
                it.userName
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
    val comparator = CredentialEntryInfoComparatorByTypeThenTimestamp()
    // Sort per username
    userNameToCredentialEntryMap.values.forEach {
        it.sortWith(comparator)
    }
    // Transform to list of PerUserNameCredentialEntryLists and then sort across usernames
    val sortedUserNameToCredentialEntryList = userNameToCredentialEntryMap.map {
        PerUserNameCredentialEntryList(it.key, it.value)
    }.sortedWith(
        compareByDescending { it.sortedCredentialEntryList.first().lastUsedTimeMillis }
    )

    return ProviderDisplayInfo(
        sortedUserNameToCredentialEntryList = sortedUserNameToCredentialEntryList,
        authenticationEntryList = authenticationEntryList,
        remoteEntry = remoteEntryList.getOrNull(0),
    )
}

private fun toActiveEntry(
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
    providerDisplayInfo: ProviderDisplayInfo
): GetScreenState {
    return if (providerDisplayInfo.sortedUserNameToCredentialEntryList.isEmpty() &&
        providerDisplayInfo.remoteEntry == null &&
        providerDisplayInfo.authenticationEntryList.all { it.isUnlockedAndEmpty })
        GetScreenState.UNLOCKED_AUTH_ENTRIES_ONLY
    else if (providerDisplayInfo.sortedUserNameToCredentialEntryList.isEmpty() &&
        providerDisplayInfo.authenticationEntryList.isEmpty() &&
        providerDisplayInfo.remoteEntry != null)
        GetScreenState.REMOTE_ONLY
    else GetScreenState.PRIMARY_SELECTION
}

internal class CredentialEntryInfoComparatorByTypeThenTimestamp : Comparator<CredentialEntryInfo> {
    override fun compare(p0: CredentialEntryInfo, p1: CredentialEntryInfo): Int {
        // First prefer passkey type for its security benefits
        if (p0.credentialType != p1.credentialType) {
            if (CredentialType.PASSKEY == p0.credentialType) {
                return -1
            } else if (CredentialType.PASSKEY == p1.credentialType) {
                return 1
            }
        }
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
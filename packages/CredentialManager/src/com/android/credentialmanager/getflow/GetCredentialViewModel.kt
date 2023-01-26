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

import android.app.Activity
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.android.credentialmanager.CredentialManagerRepo
import com.android.credentialmanager.common.Constants
import com.android.credentialmanager.common.DialogState
import com.android.credentialmanager.common.ProviderActivityResult
import com.android.credentialmanager.common.ProviderActivityState
import com.android.credentialmanager.jetpack.developer.PublicKeyCredential
import com.android.internal.util.Preconditions

data class GetCredentialUiState(
    val providerInfoList: List<ProviderInfo>,
    val requestDisplayInfo: RequestDisplayInfo,
    val currentScreenState: GetScreenState = toGetScreenState(providerInfoList),
    val providerDisplayInfo: ProviderDisplayInfo = toProviderDisplayInfo(providerInfoList),
    val selectedEntry: EntryInfo? = null,
    val activeEntry: EntryInfo? = toActiveEntry(providerDisplayInfo),
    val providerActivityState: ProviderActivityState =
        ProviderActivityState.NOT_APPLICABLE,
    val isNoAccount: Boolean = false,
    val dialogState: DialogState = DialogState.ACTIVE,
)

class GetCredentialViewModel(
    private val credManRepo: CredentialManagerRepo,
    initialUiState: GetCredentialUiState,
) : ViewModel() {

    var uiState by mutableStateOf(initialUiState)
        private set

    fun onEntrySelected(entry: EntryInfo) {
        Log.d(Constants.LOG_TAG, "credential selected: {provider=${entry.providerId}" +
            ", key=${entry.entryKey}, subkey=${entry.entrySubkey}}")
        if (entry.pendingIntent != null) {
            uiState = uiState.copy(
                selectedEntry = entry,
                providerActivityState = ProviderActivityState.READY_TO_LAUNCH,
            )
        } else {
            credManRepo.onOptionSelected(entry.providerId, entry.entryKey, entry.entrySubkey)
            uiState = uiState.copy(dialogState = DialogState.COMPLETE)
        }
    }

    fun onConfirmEntrySelected() {
        val activeEntry = uiState.activeEntry
        if (activeEntry != null) {
            onEntrySelected(activeEntry)
        } else {
            Log.d(Constants.LOG_TAG,
                "Illegal state: confirm is pressed but activeEntry isn't set.")
            onInternalError()
        }
    }

    fun launchProviderUi(
        launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>
    ) {
        val entry = uiState.selectedEntry
        if (entry != null && entry.pendingIntent != null) {
            Log.d(Constants.LOG_TAG, "Launching provider activity")
            uiState = uiState.copy(providerActivityState = ProviderActivityState.PENDING)
            val intentSenderRequest = IntentSenderRequest.Builder(entry.pendingIntent)
                .setFillInIntent(entry.fillInIntent).build()
            launcher.launch(intentSenderRequest)
        } else {
            Log.d(Constants.LOG_TAG, "No provider UI to launch")
            onInternalError()
        }
    }

    // When the view model runs into unexpected illegal state, reports the error back and close
    // the activity gracefully.
    private fun onInternalError() {
        Log.w(Constants.LOG_TAG, "UI closed due to illegal internal state")
        credManRepo.onParsingFailureCancel()
        uiState = uiState.copy(dialogState = DialogState.COMPLETE)
    }

    fun onProviderActivityResult(providerActivityResult: ProviderActivityResult) {
        val entry = uiState.selectedEntry
        val resultCode = providerActivityResult.resultCode
        val resultData = providerActivityResult.data
        if (resultCode == Activity.RESULT_CANCELED) {
            // Re-display the CredMan UI if the user canceled from the provider UI.
            Log.d(Constants.LOG_TAG, "The provider activity was cancelled," +
                " re-displaying our UI.")
            uiState = uiState.copy(
                selectedEntry = null,
                providerActivityState = ProviderActivityState.NOT_APPLICABLE,
            )
        } else {
            if (entry != null) {
                Log.d(
                    Constants.LOG_TAG, "Got provider activity result: {provider=" +
                    "${entry.providerId}, key=${entry.entryKey}, subkey=${entry.entrySubkey}" +
                    ", resultCode=$resultCode, resultData=$resultData}"
                )
                credManRepo.onOptionSelected(
                    entry.providerId, entry.entryKey, entry.entrySubkey,
                    resultCode, resultData,
                )
                uiState = uiState.copy(dialogState = DialogState.COMPLETE)
            } else {
                Log.w(Constants.LOG_TAG,
                    "Illegal state: received a provider result but found no matching entry.")
                onInternalError()
            }
        }
    }

    fun onMoreOptionSelected() {
        Log.d(Constants.LOG_TAG, "More Option selected")
        uiState = uiState.copy(
            currentScreenState = GetScreenState.ALL_SIGN_IN_OPTIONS
        )
    }

    fun onMoreOptionOnSnackBarSelected(isNoAccount: Boolean) {
        Log.d(Constants.LOG_TAG, "More Option on snackBar selected")
        uiState = uiState.copy(
            currentScreenState = GetScreenState.ALL_SIGN_IN_OPTIONS,
            isNoAccount = isNoAccount,
        )
    }

    fun onBackToPrimarySelectionScreen() {
        uiState = uiState.copy(
            currentScreenState = GetScreenState.PRIMARY_SELECTION
        )
    }

    fun onCancel() {
        credManRepo.onUserCancel()
        uiState = uiState.copy(dialogState = DialogState.COMPLETE)
    }
}

private fun toProviderDisplayInfo(
    providerInfoList: List<ProviderInfo>
): ProviderDisplayInfo {

    val userNameToCredentialEntryMap = mutableMapOf<String, MutableList<CredentialEntryInfo>>()
    val authenticationEntryList = mutableListOf<AuthenticationEntryInfo>()
    val remoteEntryList = mutableListOf<RemoteEntryInfo>()
    providerInfoList.forEach { providerInfo ->
        if (providerInfo.authenticationEntry != null) {
            authenticationEntryList.add(providerInfo.authenticationEntry)
        }
        if (providerInfo.remoteEntry != null) {
            remoteEntryList.add(providerInfo.remoteEntry)
        }

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
    // There can only be at most one remote entry
    // TODO: fail elegantly
    Preconditions.checkState(remoteEntryList.size <= 1)

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
    providerInfoList: List<ProviderInfo>
): GetScreenState {
    var noLocalAccount = true
    var remoteInfo: RemoteEntryInfo? = null
    providerInfoList.forEach { providerInfo ->
        if (providerInfo.credentialEntryList.isNotEmpty() ||
            providerInfo.authenticationEntry != null) {
            noLocalAccount = false
        }
        // TODO: handle the error situation that if multiple remoteInfos exists
        if (providerInfo.remoteEntry != null) {
            remoteInfo = providerInfo.remoteEntry
        }
    }

    return if (noLocalAccount && remoteInfo != null)
        GetScreenState.REMOTE_ONLY else GetScreenState.PRIMARY_SELECTION
}

internal class CredentialEntryInfoComparatorByTypeThenTimestamp : Comparator<CredentialEntryInfo> {
    override fun compare(p0: CredentialEntryInfo, p1: CredentialEntryInfo): Int {
        // First prefer passkey type for its security benefits
        if (p0.credentialType != p1.credentialType) {
            if (PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL == p0.credentialType) {
                return -1
            } else if (PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL == p1.credentialType) {
                return 1
            }
        }

        // Then order by last used timestamp
        if (p0.lastUsedTimeMillis != null && p1.lastUsedTimeMillis != null) {
            if (p0.lastUsedTimeMillis < p1.lastUsedTimeMillis) {
                return 1
            } else if (p0.lastUsedTimeMillis > p1.lastUsedTimeMillis) {
                return -1
            }
        } else if (p0.lastUsedTimeMillis != null && p0.lastUsedTimeMillis > 0) {
            return -1
        } else if (p1.lastUsedTimeMillis != null && p1.lastUsedTimeMillis > 0) {
            return 1
        }
        return 0
    }
}
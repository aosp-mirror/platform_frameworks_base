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

import android.app.Activity
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.android.credentialmanager.common.Constants
import com.android.credentialmanager.CreateFlowUtils
import com.android.credentialmanager.CredentialManagerRepo
import com.android.credentialmanager.UserConfigRepo
import com.android.credentialmanager.common.DialogState
import com.android.credentialmanager.common.ProviderActivityResult
import com.android.credentialmanager.common.ProviderActivityState


class CreateCredentialViewModel(
    private val credManRepo: CredentialManagerRepo,
    private val providerEnableListUiState: List<EnabledProviderInfo>,
    private val providerDisableListUiState: List<DisabledProviderInfo>,
    private val requestDisplayInfoUiState: RequestDisplayInfo,
    userConfigRepo: UserConfigRepo = UserConfigRepo.getInstance(),
) : ViewModel() {

    val defaultProviderId = userConfigRepo.getDefaultProviderId()
    val isPasskeyFirstUse = userConfigRepo.getIsPasskeyFirstUse()

    var uiState by mutableStateOf(
        CreateFlowUtils.toCreateCredentialUiState(
            providerEnableListUiState,
            providerDisableListUiState,
            defaultProviderId,
            requestDisplayInfoUiState,
            false,
            isPasskeyFirstUse)!!)
        private set

    fun onConfirmIntro() {
        val newUiState = CreateFlowUtils.toCreateCredentialUiState(
            providerEnableListUiState, providerDisableListUiState, defaultProviderId,
            requestDisplayInfoUiState, true, isPasskeyFirstUse)
        if (newUiState == null) {
            onInternalError()
            return
        }
        uiState = newUiState
        UserConfigRepo.getInstance().setIsPasskeyFirstUse(false)
    }

    fun getProviderInfoByName(providerId: String): EnabledProviderInfo {
        return uiState.enabledProviders.single {
            it.id == providerId
        }
    }

    fun onMoreOptionsSelectedOnProviderSelection() {
        uiState = uiState.copy(
            currentScreenState = CreateScreenState.MORE_OPTIONS_SELECTION,
            isFromProviderSelection = true
        )
    }

    fun onMoreOptionsSelectedOnCreationSelection() {
        uiState = uiState.copy(
            currentScreenState = CreateScreenState.MORE_OPTIONS_SELECTION,
            isFromProviderSelection = false
        )
    }

    fun onBackProviderSelectionButtonSelected() {
        uiState = uiState.copy(
            currentScreenState = CreateScreenState.PROVIDER_SELECTION,
        )
    }

    fun onBackCreationSelectionButtonSelected() {
        uiState = uiState.copy(
            currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
        )
    }

    fun onBackPasskeyIntroButtonSelected() {
        uiState = uiState.copy(
            currentScreenState = CreateScreenState.PASSKEY_INTRO,
        )
    }

    fun onEntrySelectedFromMoreOptionScreen(activeEntry: ActiveEntry) {
        uiState = uiState.copy(
            currentScreenState =
            if (activeEntry.activeProvider.id ==
                UserConfigRepo.getInstance().getDefaultProviderId())
                CreateScreenState.CREATION_OPTION_SELECTION
            else CreateScreenState.MORE_OPTIONS_ROW_INTRO,
            activeEntry = activeEntry
        )
    }

    fun onEntrySelectedFromFirstUseScreen(activeEntry: ActiveEntry) {
        uiState = uiState.copy(
            currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
            activeEntry = activeEntry
        )
        val providerId = uiState.activeEntry?.activeProvider?.id
        onDefaultChanged(providerId)
    }

    fun onDisabledProvidersSelected() {
        credManRepo.onSettingLaunchCancel()
        uiState = uiState.copy(dialogState = DialogState.CANCELED_FOR_SETTINGS)
    }

    // When the view model runs into unexpected illegal state, reports the error back and close
    // the activity gracefully.
    private fun onInternalError() {
        Log.w(Constants.LOG_TAG, "UI closed due to illegal internal state")
        credManRepo.onParsingFailureCancel()
        uiState = uiState.copy(dialogState = DialogState.COMPLETE)
    }

    fun onCancel() {
        credManRepo.onUserCancel()
        uiState = uiState.copy(dialogState = DialogState.COMPLETE)
    }

    fun onLearnMore() {
        uiState = uiState.copy(
            currentScreenState = CreateScreenState.MORE_ABOUT_PASSKEYS_INTRO,
        )
    }

    fun onChangeDefaultSelected() {
        uiState = uiState.copy(
            currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
        )
        val providerId = uiState.activeEntry?.activeProvider?.id
        onDefaultChanged(providerId)
    }

    fun onUseOnceSelected() {
        uiState = uiState.copy(
            currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
        )
    }

    fun onDefaultChanged(providerId: String?) {
        if (providerId != null) {
            Log.d(
                Constants.LOG_TAG, "Default provider changed to: " +
                " {provider=$providerId")
            UserConfigRepo.getInstance().setDefaultProvider(providerId)
        } else {
            Log.w(Constants.LOG_TAG, "Null provider is being changed")
        }
    }

    fun onEntrySelected(selectedEntry: EntryInfo) {
        val providerId = selectedEntry.providerId
        val entryKey = selectedEntry.entryKey
        val entrySubkey = selectedEntry.entrySubkey
        Log.d(
            Constants.LOG_TAG, "Option selected for entry: " +
            " {provider=$providerId, key=$entryKey, subkey=$entrySubkey")
        if (selectedEntry.pendingIntent != null) {
            uiState = uiState.copy(
                selectedEntry = selectedEntry,
                providerActivityState = ProviderActivityState.READY_TO_LAUNCH,
            )
        } else {
            credManRepo.onOptionSelected(
                providerId,
                entryKey,
                entrySubkey
            )
            uiState = uiState.copy(dialogState = DialogState.COMPLETE)
        }
    }

    fun launchProviderUi(
        launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>
    ) {
        val entry = uiState.selectedEntry
        if (entry != null && entry.pendingIntent != null) {
            uiState = uiState.copy(providerActivityState = ProviderActivityState.PENDING)
            val intentSenderRequest = IntentSenderRequest.Builder(entry.pendingIntent)
                .setFillInIntent(entry.fillInIntent).build()
            launcher.launch(intentSenderRequest)
        } else {
            Log.d(Constants.LOG_TAG, "Unexpected: no provider UI to launch")
            onInternalError()
        }
    }

    fun onConfirmEntrySelected() {
        val selectedEntry = uiState.activeEntry?.activeEntryInfo
        if (selectedEntry != null) {
            onEntrySelected(selectedEntry)
        } else {
            Log.d(Constants.LOG_TAG,
                "Unexpected: confirm is pressed but no active entry exists.")
            onInternalError()
        }
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
                val providerId = entry.providerId
                Log.d(Constants.LOG_TAG, "Got provider activity result: {provider=" +
                    "$providerId, key=${entry.entryKey}, subkey=${entry.entrySubkey}, " +
                    "resultCode=$resultCode, resultData=$resultData}"
                )
                credManRepo.onOptionSelected(
                    providerId, entry.entryKey, entry.entrySubkey, resultCode, resultData,
                )
                uiState = uiState.copy(dialogState = DialogState.COMPLETE)
            } else {
                Log.d(Constants.LOG_TAG,
                    "Illegal state: received a provider result but found no matching entry.")
                onInternalError()
            }
        }
    }

    companion object Factory {
        // Validates the input and returns null if the input is invalid.
        fun newInstance(
            credManRepo: CredentialManagerRepo,
            providerEnableListUiState: List<EnabledProviderInfo>,
            providerDisableListUiState: List<DisabledProviderInfo>,
            requestDisplayInfoUiState: RequestDisplayInfo?,
        ): CreateCredentialViewModel? {
            if (providerEnableListUiState.isEmpty() || requestDisplayInfoUiState == null) {
                return null
            }
            return try {
                val result = CreateCredentialViewModel(
                    credManRepo = credManRepo,
                    providerEnableListUiState = providerEnableListUiState,
                    providerDisableListUiState = providerDisableListUiState,
                    requestDisplayInfoUiState = requestDisplayInfoUiState
                )
                result
            } catch (e: Exception) {
                null
            }
        }
    }
}

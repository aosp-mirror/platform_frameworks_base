/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.credentialmanager

import android.app.Activity
import android.os.IBinder
import android.text.TextUtils
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.android.credentialmanager.common.BaseEntry
import com.android.credentialmanager.common.Constants
import com.android.credentialmanager.common.DialogState
import com.android.credentialmanager.common.ProviderActivityResult
import com.android.credentialmanager.common.ProviderActivityState
import com.android.credentialmanager.createflow.ActiveEntry
import com.android.credentialmanager.createflow.CreateCredentialUiState
import com.android.credentialmanager.createflow.CreateScreenState
import com.android.credentialmanager.getflow.GetCredentialUiState
import com.android.credentialmanager.getflow.GetScreenState
import com.android.credentialmanager.logging.LifecycleEvent
import com.android.credentialmanager.logging.UIMetrics
import com.android.internal.logging.UiEventLogger.UiEventEnum

/** One and only one of create or get state can be active at any given time. */
data class UiState(
    val createCredentialUiState: CreateCredentialUiState?,
    val getCredentialUiState: GetCredentialUiState?,
    val selectedEntry: BaseEntry? = null,
    val providerActivityState: ProviderActivityState = ProviderActivityState.NOT_APPLICABLE,
    val dialogState: DialogState = DialogState.ACTIVE,
    // True if the UI has one and only one auto selectable entry. Its provider activity will be
    // launched immediately, and canceling it will cancel the whole UI flow.
    val isAutoSelectFlow: Boolean = false,
    val cancelRequestState: CancelUiRequestState?,
    val isInitialRender: Boolean,
)

data class CancelUiRequestState(
    val appDisplayName: String?,
)

class CredentialSelectorViewModel(
    private var credManRepo: CredentialManagerRepo,
    private val userConfigRepo: UserConfigRepo,
) : ViewModel() {
    var uiState by mutableStateOf(credManRepo.initState())
        private set

    var uiMetrics: UIMetrics = UIMetrics()

    init {
        uiMetrics.logNormal(LifecycleEvent.CREDMAN_ACTIVITY_INIT,
            credManRepo.requestInfo?.appPackageName)
    }

    /**************************************************************************/
    /*****                       Shared Callbacks                         *****/
    /**************************************************************************/
    fun onUserCancel() {
        Log.d(Constants.LOG_TAG, "User cancelled, finishing the ui")
        credManRepo.onUserCancel()
        uiState = uiState.copy(dialogState = DialogState.COMPLETE)
    }

    fun onInitialRenderComplete() {
        uiState = uiState.copy(isInitialRender = false)
    }

    fun onCancellationUiRequested(appDisplayName: String?) {
        uiState = uiState.copy(cancelRequestState = CancelUiRequestState(appDisplayName))
    }

    /** Close the activity and don't report anything to the backend.
     *  Example use case is the no-auth-info snackbar where the activity should simply display the
     *  UI and then be dismissed. */
    fun silentlyFinishActivity() {
        Log.d(Constants.LOG_TAG, "Silently finishing the ui")
        uiState = uiState.copy(dialogState = DialogState.COMPLETE)
    }

    fun onNewCredentialManagerRepo(credManRepo: CredentialManagerRepo) {
        this.credManRepo = credManRepo
        uiState = credManRepo.initState().copy(isInitialRender = false)

        if (this.credManRepo.requestInfo?.token != credManRepo.requestInfo?.token) {
            this.uiMetrics.resetInstanceId()
            this.uiMetrics.logNormal(LifecycleEvent.CREDMAN_ACTIVITY_NEW_REQUEST,
                credManRepo.requestInfo?.appPackageName)
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
            try {
                launcher.launch(intentSenderRequest)
            } catch (e: Exception) {
                Log.w(Constants.LOG_TAG, "Failed to launch provider UI: $e")
                onInternalError()
            }
        } else {
            Log.d(Constants.LOG_TAG, "No provider UI to launch")
            onInternalError()
        }
    }

    fun onProviderActivityResult(providerActivityResult: ProviderActivityResult) {
        val entry = uiState.selectedEntry
        val resultCode = providerActivityResult.resultCode
        val resultData = providerActivityResult.data
        if (resultCode == Activity.RESULT_CANCELED) {
            // Re-display the CredMan UI if the user canceled from the provider UI, or cancel
            // the UI if this is the auto select flow.
            if (uiState.isAutoSelectFlow) {
                Log.d(Constants.LOG_TAG, "The auto selected provider activity was cancelled," +
                    " ending the credential manager activity.")
                onUserCancel()
            } else {
                Log.d(Constants.LOG_TAG, "The provider activity was cancelled," +
                    " re-displaying our UI.")
                uiState = uiState.copy(
                    selectedEntry = null,
                    providerActivityState = ProviderActivityState.NOT_APPLICABLE,
                )
            }
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
                if (entry.shouldTerminateUiUponSuccessfulProviderResult) {
                    uiState = uiState.copy(dialogState = DialogState.COMPLETE)
                }
            } else {
                Log.w(Constants.LOG_TAG,
                    "Illegal state: received a provider result but found no matching entry.")
                onInternalError()
            }
        }
    }

    fun onLastLockedAuthEntryNotFoundError() {
        Log.d(Constants.LOG_TAG, "Unable to find the last unlocked entry")
        onInternalError()
    }

    fun onIllegalUiState(errorMessage: String) {
        Log.w(Constants.LOG_TAG, errorMessage)
        onInternalError()
    }

    private fun onInternalError() {
        Log.w(Constants.LOG_TAG, "UI closed due to illegal internal state")
        this.uiMetrics.logNormal(LifecycleEvent.CREDMAN_ACTIVITY_INTERNAL_ERROR,
            credManRepo.requestInfo?.appPackageName)
        credManRepo.onParsingFailureCancel()
        uiState = uiState.copy(dialogState = DialogState.COMPLETE)
    }

    /** Return true if the current UI's request token matches the UI cancellation request token. */
    fun shouldCancelCurrentUi(cancelRequestToken: IBinder): Boolean {
        return credManRepo.requestInfo?.token?.equals(cancelRequestToken) ?: false
    }

    /**************************************************************************/
    /*****                      Get Flow Callbacks                        *****/
    /**************************************************************************/
    fun getFlowOnEntrySelected(entry: BaseEntry) {
        Log.d(Constants.LOG_TAG, "credential selected: {provider=${entry.providerId}" +
            ", key=${entry.entryKey}, subkey=${entry.entrySubkey}}")
        uiState = if (entry.pendingIntent != null) {
            uiState.copy(
                selectedEntry = entry,
                providerActivityState = ProviderActivityState.READY_TO_LAUNCH,
            )
        } else {
            credManRepo.onOptionSelected(entry.providerId, entry.entryKey, entry.entrySubkey)
            uiState.copy(dialogState = DialogState.COMPLETE)
        }
    }

    fun getFlowOnConfirmEntrySelected() {
        val activeEntry = uiState.getCredentialUiState?.activeEntry
        if (activeEntry != null) {
            getFlowOnEntrySelected(activeEntry)
        } else {
            Log.d(Constants.LOG_TAG,
                "Illegal state: confirm is pressed but activeEntry isn't set.")
            onInternalError()
        }
    }

    fun getFlowOnMoreOptionSelected() {
        Log.d(Constants.LOG_TAG, "More Option selected")
        uiState = uiState.copy(
            getCredentialUiState = uiState.getCredentialUiState?.copy(
                currentScreenState = GetScreenState.ALL_SIGN_IN_OPTIONS
            )
        )
    }

    fun getFlowOnMoreOptionOnSnackBarSelected(isNoAccount: Boolean) {
        Log.d(Constants.LOG_TAG, "More Option on snackBar selected")
        uiState = uiState.copy(
            getCredentialUiState = uiState.getCredentialUiState?.copy(
                currentScreenState = GetScreenState.ALL_SIGN_IN_OPTIONS,
                isNoAccount = isNoAccount,
            )
        )
    }

    fun getFlowOnBackToHybridSnackBarScreen() {
        uiState = uiState.copy(
            getCredentialUiState = uiState.getCredentialUiState?.copy(
                currentScreenState = GetScreenState.REMOTE_ONLY
            )
        )
    }

    fun getFlowOnBackToPrimarySelectionScreen() {
        uiState = uiState.copy(
            getCredentialUiState = uiState.getCredentialUiState?.copy(
                currentScreenState = GetScreenState.PRIMARY_SELECTION
            )
        )
    }

    /**************************************************************************/
    /*****                     Create Flow Callbacks                      *****/
    /**************************************************************************/
    fun createFlowOnConfirmIntro() {
        val prevUiState = uiState.createCredentialUiState
        if (prevUiState == null) {
            Log.d(Constants.LOG_TAG, "Encountered unexpected null create ui state")
            onInternalError()
            return
        }
        val newUiState = CreateFlowUtils.toCreateCredentialUiState(
            enabledProviders = prevUiState.enabledProviders,
            disabledProviders = prevUiState.disabledProviders,
            defaultProviderIdPreferredByApp =
            prevUiState.requestDisplayInfo.appPreferredDefaultProviderId,
            defaultProviderIdsSetByUser =
            prevUiState.requestDisplayInfo.userSetDefaultProviderIds,
            requestDisplayInfo = prevUiState.requestDisplayInfo,
            isOnPasskeyIntroStateAlready = true,
            isPasskeyFirstUse = userConfigRepo.getIsPasskeyFirstUse()
        )
        if (newUiState == null) {
            Log.d(Constants.LOG_TAG, "Unable to update create ui state")
            onInternalError()
            return
        }
        uiState = uiState.copy(createCredentialUiState = newUiState)
        userConfigRepo.setIsPasskeyFirstUse(false)
    }

    fun createFlowOnMoreOptionsSelectedOnCreationSelection() {
        uiState = uiState.copy(
            createCredentialUiState = uiState.createCredentialUiState?.copy(
                currentScreenState = CreateScreenState.MORE_OPTIONS_SELECTION,
            )
        )
    }

    fun createFlowOnBackCreationSelectionButtonSelected() {
        uiState = uiState.copy(
            createCredentialUiState = uiState.createCredentialUiState?.copy(
                currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
            )
        )
    }

    fun createFlowOnBackPasskeyIntroButtonSelected() {
        uiState = uiState.copy(
            createCredentialUiState = uiState.createCredentialUiState?.copy(
                currentScreenState = CreateScreenState.PASSKEY_INTRO,
            )
        )
    }

    fun createFlowOnEntrySelectedFromMoreOptionScreen(activeEntry: ActiveEntry) {
        uiState = uiState.copy(
            createCredentialUiState = uiState.createCredentialUiState?.copy(
                currentScreenState =
                if (uiState.createCredentialUiState?.requestDisplayInfo?.userSetDefaultProviderIds
                        ?.contains(activeEntry.activeProvider.id) ?: true ||
                    !(uiState.createCredentialUiState?.foundCandidateFromUserDefaultProvider
                    ?: false) ||
                    !TextUtils.isEmpty(uiState.createCredentialUiState?.requestDisplayInfo
                        ?.appPreferredDefaultProviderId))
                    CreateScreenState.CREATION_OPTION_SELECTION
                else CreateScreenState.DEFAULT_PROVIDER_CONFIRMATION,
                activeEntry = activeEntry
            )
        )
    }

    fun createFlowOnLaunchSettings() {
        credManRepo.onSettingLaunchCancel()
        uiState = uiState.copy(dialogState = DialogState.CANCELED_FOR_SETTINGS)
    }

    fun createFlowOnLearnMore() {
        uiState = uiState.copy(
            createCredentialUiState = uiState.createCredentialUiState?.copy(
                currentScreenState = CreateScreenState.MORE_ABOUT_PASSKEYS_INTRO,
            )
        )
    }

    fun createFlowOnUseOnceSelected() {
        uiState = uiState.copy(
            createCredentialUiState = uiState.createCredentialUiState?.copy(
                currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
            )
        )
    }

    fun createFlowOnEntrySelected(selectedEntry: BaseEntry) {
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

    fun createFlowOnConfirmEntrySelected() {
        val selectedEntry = uiState.createCredentialUiState?.activeEntry?.activeEntryInfo
        if (selectedEntry != null) {
            createFlowOnEntrySelected(selectedEntry)
        } else {
            Log.d(Constants.LOG_TAG,
                "Unexpected: confirm is pressed but no active entry exists.")
            onInternalError()
        }
    }

    @Composable
    fun logUiEvent(uiEventEnum: UiEventEnum) {
        this.uiMetrics.log(uiEventEnum, credManRepo.requestInfo?.appPackageName)
    }
}
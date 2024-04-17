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
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.BiometricPrompt.AuthenticationResult
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
import com.android.credentialmanager.common.BiometricError
import com.android.credentialmanager.common.BiometricFlowType
import com.android.credentialmanager.common.BiometricPromptState
import com.android.credentialmanager.common.BiometricResult
import com.android.credentialmanager.common.BiometricState
import com.android.credentialmanager.model.EntryInfo
import com.android.credentialmanager.common.Constants
import com.android.credentialmanager.common.DialogState
import com.android.credentialmanager.common.ProviderActivityResult
import com.android.credentialmanager.common.ProviderActivityState
import com.android.credentialmanager.createflow.ActiveEntry
import com.android.credentialmanager.createflow.CreateCredentialUiState
import com.android.credentialmanager.createflow.CreateScreenState
import com.android.credentialmanager.createflow.isBiometricFlow
import com.android.credentialmanager.getflow.GetCredentialUiState
import com.android.credentialmanager.getflow.GetScreenState
import com.android.credentialmanager.logging.LifecycleEvent
import com.android.credentialmanager.logging.UIMetrics
import com.android.internal.logging.UiEventLogger.UiEventEnum

/** One and only one of create or get state can be active at any given time. */
data class UiState(
    val createCredentialUiState: CreateCredentialUiState?,
    val getCredentialUiState: GetCredentialUiState?,
    val selectedEntry: EntryInfo? = null,
    val providerActivityState: ProviderActivityState = ProviderActivityState.NOT_APPLICABLE,
    val dialogState: DialogState = DialogState.ACTIVE,
    // True if the UI has one and only one auto selectable entry. Its provider activity will be
    // launched immediately, and canceling it will cancel the whole UI flow.
    val isAutoSelectFlow: Boolean = false,
    val cancelRequestState: CancelUiRequestState?,
    val isInitialRender: Boolean,
    val biometricState: BiometricState = BiometricState()
)

data class CancelUiRequestState(
    val appDisplayName: String?,
)

class CredentialSelectorViewModel(
    private var credManRepo: CredentialManagerRepo,
) : ViewModel() {
    var uiState by mutableStateOf(credManRepo.initState())
        private set

    var uiMetrics: UIMetrics = UIMetrics()

    init {
        uiMetrics.logNormal(LifecycleEvent.CREDMAN_ACTIVITY_INIT,
            credManRepo.requestInfo?.packageName)
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
                credManRepo.requestInfo?.packageName)
        }
    }

    fun launchProviderUi(
        launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>
    ) {
        val entry = uiState.selectedEntry
        val biometricState = uiState.biometricState
        val pendingIntent = entry?.pendingIntent
        if (pendingIntent != null) {
            Log.d(Constants.LOG_TAG, "Launching provider activity")
            uiState = uiState.copy(providerActivityState = ProviderActivityState.PENDING)
            val entryIntent = entry.fillInIntent
            entryIntent?.putExtra(Constants.IS_AUTO_SELECTED_KEY, uiState.isAutoSelectFlow)
            if (biometricState.biometricResult != null || biometricState.biometricError != null) {
                if (uiState.isAutoSelectFlow) {
                    Log.w(Constants.LOG_TAG, "Unexpected biometric result exists when " +
                            "autoSelect is preferred.")
                }
                // TODO(b/333445754) : Decide whether to propagate info on prompt launch
                if (biometricState.biometricResult != null) {
                    entryIntent?.putExtra(Constants.BIOMETRIC_AUTH_RESULT,
                        biometricState.biometricResult.biometricAuthenticationResult
                            .authenticationType)
                } else if (biometricState.biometricError != null){
                    entryIntent?.putExtra(Constants.BIOMETRIC_AUTH_ERROR_CODE,
                        biometricState.biometricError.errorCode)
                    entryIntent?.putExtra(Constants.BIOMETRIC_AUTH_ERROR_MESSAGE,
                        biometricState.biometricError.errorMessage)
                }
            }
            val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent)
                .setFillInIntent(entryIntent).build()
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
            credManRepo.requestInfo?.packageName)
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
    fun getFlowOnEntrySelected(
        entry: EntryInfo,
        authResult: BiometricPrompt.AuthenticationResult? = null,
        authError: BiometricError? = null,
    ) {
        Log.d(Constants.LOG_TAG, "credential selected: {provider=${entry.providerId}" +
            ", key=${entry.entryKey}, subkey=${entry.entrySubkey}}")
        uiState = if (entry.pendingIntent != null) {
            uiState.copy(
                selectedEntry = entry,
                providerActivityState = ProviderActivityState.READY_TO_LAUNCH,
                biometricState = if (authResult == null && authError == null)
                    uiState.biometricState else if (authResult != null) uiState
                    .biometricState.copy(biometricResult = BiometricResult(
                            biometricAuthenticationResult = authResult)) else uiState
                    .biometricState.copy(biometricError = authError)
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

    fun getFlowOnMoreOptionOnlySelected() {
        Log.d(Constants.LOG_TAG, "More Option Only selected")
        uiState = uiState.copy(
                getCredentialUiState = uiState.getCredentialUiState?.copy(
                        currentScreenState = GetScreenState.ALL_SIGN_IN_OPTIONS_ONLY
                )
        )
    }

    fun getFlowOnMoreOptionOnSnackBarSelected(isNoAccount: Boolean) {
        Log.d(Constants.LOG_TAG, "More Option on snackBar selected")
        uiState = uiState.copy(
            getCredentialUiState = uiState.getCredentialUiState?.copy(
                currentScreenState = GetScreenState.ALL_SIGN_IN_OPTIONS,
                isNoAccount = isNoAccount,
            ),
            isInitialRender = true,
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
    fun createFlowOnMoreOptionsSelectedOnCreationSelection() {
        uiState = uiState.copy(
            createCredentialUiState = uiState.createCredentialUiState?.copy(
                currentScreenState = CreateScreenState.MORE_OPTIONS_SELECTION,
            )
        )
    }

    fun createFlowOnMoreOptionsOnlySelectedOnCreationSelection() {
        uiState = uiState.copy(
                createCredentialUiState = uiState.createCredentialUiState?.copy(
                        currentScreenState = CreateScreenState.MORE_OPTIONS_SELECTION_ONLY,
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

    fun createFlowOnEntrySelectedFromMoreOptionScreen(activeEntry: ActiveEntry) {
        val isBiometricFlow = isBiometricFlow(activeEntry = activeEntry, isAutoSelectFlow = false)
        if (isBiometricFlow) {
            // This atomically ensures that the only edge case that *restarts* the biometric flow
            // doesn't risk a configuration change bug on the more options page during create.
            // Namely, it's atomic in that it happens only on a tap, and it is not possible to
            // reproduce a tap and a rotation at the same time. However, even if it were, it would
            // just be an alternate way to jump back into the biometric selection flow after this
            // reset, and thus, the state machine is maintained.
            onBiometricPromptStateChange(BiometricPromptState.INACTIVE)
        }
        uiState = uiState.copy(
            createCredentialUiState = uiState.createCredentialUiState?.copy(
                currentScreenState =
                // An autoselect flow never makes it to the more options screen
                if (isBiometricFlow) {
                    CreateScreenState.BIOMETRIC_SELECTION
                } else if (
                    uiState.createCredentialUiState?.requestDisplayInfo?.userSetDefaultProviderIds
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

    fun createFlowOnUseOnceSelected() {
        uiState = uiState.copy(
            createCredentialUiState = uiState.createCredentialUiState?.copy(
                currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
            )
        )
    }

    fun createFlowOnEntrySelected(
        selectedEntry: EntryInfo,
        authResult: AuthenticationResult? = null,
        authError: BiometricError? = null,
    ) {
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
                biometricState = if (authResult == null && authError == null)
                    uiState.biometricState else if (authResult != null) uiState
                    .biometricState.copy(biometricResult = BiometricResult(
                        biometricAuthenticationResult = authResult)) else uiState
                    .biometricState.copy(biometricError = authError)
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

    /**************************************************************************/
    /*****                     Biometric Flow Callbacks                   *****/
    /**************************************************************************/

    /**
     * This allows falling back from the biometric prompt screen to the normal get flow by applying
     * a reset to all necessary states involved in the fallback.
     */
    fun fallbackFromBiometricToNormalFlow(biometricFlowType: BiometricFlowType) {
        onBiometricPromptStateChange(BiometricPromptState.INACTIVE)
        when (biometricFlowType) {
            BiometricFlowType.GET -> getFlowOnBackToPrimarySelectionScreen()
            BiometricFlowType.CREATE -> createFlowOnUseOnceSelected()
        }
    }

    /**
     * This method can be used to change the [BiometricPromptState] according to the necessity.
     * For example, if resetting, one might use [BiometricPromptState.INACTIVE], but if the flow
     * has just launched, to avoid configuration errors, one can use
     * [BiometricPromptState.PENDING].
     */
    fun onBiometricPromptStateChange(biometricPromptState: BiometricPromptState) {
        uiState = uiState.copy(
            biometricState = uiState.biometricState.copy(
                biometricStatus = biometricPromptState
            )
        )
    }

    /**
     * This returns the present biometric state.
     */
    fun getBiometricPromptState(): BiometricPromptState =
        uiState.biometricState.biometricStatus

    /**************************************************************************/
    /*****                     Misc. Callbacks/Logs                       *****/
    /**************************************************************************/

    @Composable
    fun logUiEvent(uiEventEnum: UiEventEnum) {
        this.uiMetrics.log(uiEventEnum, credManRepo.requestInfo?.packageName)
    }
}
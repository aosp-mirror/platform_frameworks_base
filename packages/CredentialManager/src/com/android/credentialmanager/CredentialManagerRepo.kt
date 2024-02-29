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

package com.android.credentialmanager

import android.content.Context
import android.content.Intent
import android.credentials.selection.CancelSelectionRequest
import android.credentials.selection.Constants
import android.credentials.selection.CreateCredentialProviderData
import android.credentials.selection.GetCredentialProviderData
import android.credentials.selection.DisabledProviderData
import android.credentials.selection.ProviderData
import android.credentials.selection.RequestInfo
import android.credentials.selection.BaseDialogResult
import android.credentials.selection.ProviderPendingIntentResponse
import android.credentials.selection.UserSelectionDialogResult
import android.os.IBinder
import android.os.Bundle
import android.os.ResultReceiver
import android.util.Log
import android.view.autofill.AutofillManager
import com.android.credentialmanager.createflow.DisabledProviderInfo
import com.android.credentialmanager.createflow.EnabledProviderInfo
import com.android.credentialmanager.createflow.RequestDisplayInfo
import com.android.credentialmanager.getflow.GetCredentialUiState
import com.android.credentialmanager.getflow.findAutoSelectEntry
import com.android.credentialmanager.common.ProviderActivityState
import com.android.credentialmanager.createflow.isFlowAutoSelectable

/**
 * Client for interacting with Credential Manager. Also holds data inputs from it.
 *
 * IMPORTANT: instantiation of the object can fail if the data inputs aren't valid. Callers need
 * to be equipped to handle this gracefully.
 */
class CredentialManagerRepo(
    private val context: Context,
    intent: Intent,
    isNewActivity: Boolean,
) {
    val requestInfo: RequestInfo?
    var isReqForAllOptions: Boolean = false
    private val providerEnabledList: List<ProviderData>
    private val providerDisabledList: List<DisabledProviderData>?
    val resultReceiver: ResultReceiver?
    val finalResponseReceiver: ResultReceiver?

    var initialUiState: UiState

    init {
        requestInfo = intent.extras?.getParcelable(
            RequestInfo.EXTRA_REQUEST_INFO,
            RequestInfo::class.java
        )

        val originName: String? = when (requestInfo?.type) {
            RequestInfo.TYPE_CREATE -> processHttpsOrigin(
                requestInfo.createCredentialRequest?.origin)
            RequestInfo.TYPE_GET -> processHttpsOrigin(requestInfo.getCredentialRequest?.origin)
            else -> null
        }

        providerEnabledList = when (requestInfo?.type) {
            RequestInfo.TYPE_CREATE ->
                intent.extras?.getParcelableArrayList(
                    ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
                    CreateCredentialProviderData::class.java
                ) ?: emptyList()
            RequestInfo.TYPE_GET ->
                getEnabledProviderDataList(
                    intent
                ) ?: getEnabledProviderDataListFromAuthExtras(
                    intent
                ) ?: emptyList()
            else -> {
                Log.d(
                    com.android.credentialmanager.common.Constants.LOG_TAG,
                    "Unrecognized request type: ${requestInfo?.type}")
                emptyList()
            }
        }

        providerDisabledList =
            intent.extras?.getParcelableArrayList(
                ProviderData.EXTRA_DISABLED_PROVIDER_DATA_LIST,
                DisabledProviderData::class.java
            )

        resultReceiver = intent.getParcelableExtra(
            Constants.EXTRA_RESULT_RECEIVER,
            ResultReceiver::class.java
        )

        finalResponseReceiver = intent.getParcelableExtra(
                Constants.EXTRA_FINAL_RESPONSE_RECEIVER,
                ResultReceiver::class.java
        )

        isReqForAllOptions = requestInfo?.isShowAllOptionsRequested ?: false

        val cancellationRequest = getCancelUiRequest(intent)
        val cancelUiRequestState = cancellationRequest?.let {
            CancelUiRequestState(getAppLabel(context.getPackageManager(), it.packageName))
        }

        initialUiState = when (requestInfo?.type) {
            RequestInfo.TYPE_CREATE -> {
                val providerEnableListUiState = getCreateProviderEnableListInitialUiState()
                val providerDisableListUiState = getCreateProviderDisableListInitialUiState()
                val requestDisplayInfoUiState =
                    getCreateRequestDisplayInfoInitialUiState(originName)!!
                val createCredentialUiState = CreateFlowUtils.toCreateCredentialUiState(
                    enabledProviders = providerEnableListUiState,
                    disabledProviders = providerDisableListUiState,
                    defaultProviderIdPreferredByApp =
                    requestDisplayInfoUiState.appPreferredDefaultProviderId,
                    defaultProviderIdsSetByUser =
                    requestDisplayInfoUiState.userSetDefaultProviderIds,
                    requestDisplayInfo = requestDisplayInfoUiState,
                )!!
                val isFlowAutoSelectable = isFlowAutoSelectable(createCredentialUiState)
                UiState(
                    createCredentialUiState = createCredentialUiState,
                    getCredentialUiState = null,
                    cancelRequestState = cancelUiRequestState,
                    isInitialRender = isNewActivity,
                    isAutoSelectFlow = isFlowAutoSelectable,
                    providerActivityState =
                    if (isFlowAutoSelectable) ProviderActivityState.READY_TO_LAUNCH
                    else ProviderActivityState.NOT_APPLICABLE,
                    selectedEntry =
                    if (isFlowAutoSelectable) createCredentialUiState.activeEntry?.activeEntryInfo
                    else null,
                )
            }
            RequestInfo.TYPE_GET -> {
                val getCredentialInitialUiState = getCredentialInitialUiState(originName,
                        isReqForAllOptions)!!
                val autoSelectEntry =
                    findAutoSelectEntry(getCredentialInitialUiState.providerDisplayInfo)
                UiState(
                    createCredentialUiState = null,
                    getCredentialUiState = getCredentialInitialUiState,
                    selectedEntry = autoSelectEntry,
                    providerActivityState =
                    if (autoSelectEntry == null) ProviderActivityState.NOT_APPLICABLE
                    else ProviderActivityState.READY_TO_LAUNCH,
                    isAutoSelectFlow = autoSelectEntry != null,
                    cancelRequestState = cancelUiRequestState,
                    isInitialRender = isNewActivity,
                )
            }
            else -> {
                if (cancellationRequest != null) {
                    UiState(
                        createCredentialUiState = null,
                        getCredentialUiState = null,
                        cancelRequestState = cancelUiRequestState,
                        isInitialRender = isNewActivity,
                    )
                } else {
                    throw IllegalStateException("Unrecognized request type: ${requestInfo?.type}")
                }
            }
        }
    }

    fun initState(): UiState {
        return initialUiState
    }

    // The dialog is canceled by the user.
    fun onUserCancel() {
        onCancel(BaseDialogResult.RESULT_CODE_DIALOG_USER_CANCELED)
    }

    // The dialog is canceled because we launched into settings.
    fun onSettingLaunchCancel() {
        onCancel(BaseDialogResult.RESULT_CODE_CANCELED_AND_LAUNCHED_SETTINGS)
    }

    fun onParsingFailureCancel() {
        onCancel(BaseDialogResult.RESULT_CODE_DATA_PARSING_FAILURE)
    }

    fun onCancel(cancelCode: Int) {
        sendCancellationCode(cancelCode, requestInfo?.token, resultReceiver, finalResponseReceiver)
    }

    fun onOptionSelected(
        providerId: String,
        entryKey: String,
        entrySubkey: String,
        resultCode: Int? = null,
        resultData: Intent? = null,
    ) {
        val userSelectionDialogResult = UserSelectionDialogResult(
            requestInfo?.token,
            providerId,
            entryKey,
            entrySubkey,
            if (resultCode != null) ProviderPendingIntentResponse(resultCode, resultData) else null
        )
        val resultDataBundle = Bundle()
        UserSelectionDialogResult.addToBundle(userSelectionDialogResult, resultDataBundle)

        resultDataBundle.putParcelable(Constants.EXTRA_FINAL_RESPONSE_RECEIVER,
                finalResponseReceiver)

        resultReceiver?.send(
            BaseDialogResult.RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION,
            resultDataBundle
        )
    }

    // IMPORTANT: new invocation should be mindful that this method can throw.
    private fun getCredentialInitialUiState(
            originName: String?,
            isReqForAllOptions: Boolean
    ): GetCredentialUiState? {
        val providerEnabledList = GetFlowUtils.toProviderList(
            providerEnabledList as List<GetCredentialProviderData>, context
        )
        val requestDisplayInfo = GetFlowUtils.toRequestDisplayInfo(requestInfo, context, originName)
        return GetCredentialUiState(
                isReqForAllOptions,
                providerEnabledList,
                requestDisplayInfo ?: return null
        )
    }

    private fun getEnabledProviderDataList(intent: Intent): List<GetCredentialProviderData>? {
        return intent.extras?.getParcelableArrayList(
            ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
            GetCredentialProviderData::class.java
        )
    }

    private fun getEnabledProviderDataListFromAuthExtras(
        intent: Intent
    ): List<GetCredentialProviderData>? {
        return intent.getBundleExtra(
            AutofillManager.EXTRA_AUTH_STATE
        ) ?.getParcelableArrayList(
            ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
            GetCredentialProviderData::class.java
        )
    }

    // IMPORTANT: new invocation should be mindful that this method can throw.
    private fun getCreateProviderEnableListInitialUiState(): List<EnabledProviderInfo> {
        return CreateFlowUtils.toEnabledProviderList(
            providerEnabledList as List<CreateCredentialProviderData>, context
        )
    }

    private fun getCreateProviderDisableListInitialUiState(): List<DisabledProviderInfo> {
        return CreateFlowUtils.toDisabledProviderList(
            // Handle runtime cast error
            providerDisabledList, context
        )
    }

    private fun getCreateRequestDisplayInfoInitialUiState(
        originName: String?
    ): RequestDisplayInfo? {
        return CreateFlowUtils.toRequestDisplayInfo(requestInfo, context, originName)
    }

    companion object {
        private const val HTTPS = "https://"
        private const val FORWARD_SLASH = "/"

        fun sendCancellationCode(
            cancelCode: Int,
            requestToken: IBinder?,
            resultReceiver: ResultReceiver?,
            finalResponseReceiver: ResultReceiver?
        ) {
            if (requestToken != null && resultReceiver != null) {
                val resultData = Bundle()
                resultData.putParcelable(Constants.EXTRA_FINAL_RESPONSE_RECEIVER,
                        finalResponseReceiver)

                BaseDialogResult.addToBundle(BaseDialogResult(requestToken), resultData)
                resultReceiver.send(cancelCode, resultData)
            }
        }

        /** Return the cancellation request if present. */
        fun getCancelUiRequest(intent: Intent): CancelSelectionRequest? {
            return intent.extras?.getParcelable(
                CancelSelectionRequest.EXTRA_CANCEL_UI_REQUEST,
                CancelSelectionRequest::class.java
            )
        }

        /** Removes "https://", and the trailing slash if present for an https request. */
        private fun processHttpsOrigin(origin: String?): String? {
            var processed = origin
            if (processed?.startsWith(HTTPS) == true) { // Removes "https://"
                processed = processed.substring(HTTPS.length)
                if (processed?.endsWith(FORWARD_SLASH) == true) { // Removes the trailing slash
                    processed = processed.substring(0, processed.length - 1)
                }
            }
            return processed
        }
    }
}

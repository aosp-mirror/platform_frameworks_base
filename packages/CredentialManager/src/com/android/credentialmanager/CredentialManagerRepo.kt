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
import android.credentials.ui.CancelUiRequest
import android.credentials.ui.Constants
import android.credentials.ui.CreateCredentialProviderData
import android.credentials.ui.GetCredentialProviderData
import android.credentials.ui.DisabledProviderData
import android.credentials.ui.ProviderData
import android.credentials.ui.RequestInfo
import android.credentials.ui.BaseDialogResult
import android.credentials.ui.ProviderPendingIntentResponse
import android.credentials.ui.UserSelectionDialogResult
import android.os.IBinder
import android.os.Bundle
import android.os.ResultReceiver
import android.util.Log
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
    userConfigRepo: UserConfigRepo,
    isNewActivity: Boolean,
) {
    val requestInfo: RequestInfo?
    private val providerEnabledList: List<ProviderData>
    private val providerDisabledList: List<DisabledProviderData>?
    // TODO: require non-null.
    val resultReceiver: ResultReceiver?

    var initialUiState: UiState

    init {
        requestInfo = intent.extras?.getParcelable(
            RequestInfo.EXTRA_REQUEST_INFO,
            RequestInfo::class.java
        )

        val originName: String? = when (requestInfo?.type) {
            RequestInfo.TYPE_CREATE -> requestInfo.createCredentialRequest?.origin
            RequestInfo.TYPE_GET -> requestInfo.getCredentialRequest?.origin
            else -> null
        }

        providerEnabledList = when (requestInfo?.type) {
            RequestInfo.TYPE_CREATE ->
                intent.extras?.getParcelableArrayList(
                    ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
                    CreateCredentialProviderData::class.java
                ) ?: emptyList()
            RequestInfo.TYPE_GET ->
                intent.extras?.getParcelableArrayList(
                    ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
                    GetCredentialProviderData::class.java
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

        val cancellationRequest = getCancelUiRequest(intent)
        val cancelUiRequestState = cancellationRequest?.let {
            CancelUiRequestState(getAppLabel(context.getPackageManager(), it.appPackageName))
        }

        initialUiState = when (requestInfo?.type) {
            RequestInfo.TYPE_CREATE -> {
                val isPasskeyFirstUse = userConfigRepo.getIsPasskeyFirstUse()
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
                    isOnPasskeyIntroStateAlready = false,
                    isPasskeyFirstUse = isPasskeyFirstUse,
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
                val getCredentialInitialUiState = getCredentialInitialUiState(originName)!!
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
        sendCancellationCode(cancelCode, requestInfo?.token, resultReceiver)
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
        resultReceiver?.send(
            BaseDialogResult.RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION,
            resultDataBundle
        )
    }

    // IMPORTANT: new invocation should be mindful that this method can throw.
    private fun getCredentialInitialUiState(originName: String?): GetCredentialUiState? {
        val providerEnabledList = GetFlowUtils.toProviderList(
            providerEnabledList as List<GetCredentialProviderData>, context
        )
        val requestDisplayInfo = GetFlowUtils.toRequestDisplayInfo(requestInfo, context, originName)
        return GetCredentialUiState(
            providerEnabledList,
            requestDisplayInfo ?: return null,
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
        fun sendCancellationCode(
            cancelCode: Int,
            requestToken: IBinder?,
            resultReceiver: ResultReceiver?
        ) {
            if (requestToken != null && resultReceiver != null) {
                val resultData = Bundle()
                BaseDialogResult.addToBundle(BaseDialogResult(requestToken), resultData)
                resultReceiver.send(cancelCode, resultData)
            }
        }

        /** Return the cancellation request if present. */
        fun getCancelUiRequest(intent: Intent): CancelUiRequest? {
            return intent.extras?.getParcelable(
                CancelUiRequest.EXTRA_CANCEL_UI_REQUEST,
                CancelUiRequest::class.java
            )
        }
    }
}

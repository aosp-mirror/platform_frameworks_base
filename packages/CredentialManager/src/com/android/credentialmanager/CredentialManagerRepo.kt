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
import android.credentials.CreateCredentialRequest
import android.credentials.Credential.TYPE_PASSWORD_CREDENTIAL
import android.credentials.CredentialOption
import android.credentials.GetCredentialRequest
import android.credentials.ui.AuthenticationEntry
import android.credentials.ui.CancelUiRequest
import android.credentials.ui.Constants
import android.credentials.ui.Entry
import android.credentials.ui.CreateCredentialProviderData
import android.credentials.ui.GetCredentialProviderData
import android.credentials.ui.DisabledProviderData
import android.credentials.ui.ProviderData
import android.credentials.ui.RequestInfo
import android.credentials.ui.BaseDialogResult
import android.credentials.ui.ProviderPendingIntentResponse
import android.credentials.ui.UserSelectionDialogResult
import android.os.IBinder
import android.os.Binder
import android.os.Bundle
import android.os.ResultReceiver
import com.android.credentialmanager.createflow.DisabledProviderInfo
import com.android.credentialmanager.createflow.EnabledProviderInfo
import com.android.credentialmanager.createflow.RequestDisplayInfo
import com.android.credentialmanager.getflow.GetCredentialUiState
import com.android.credentialmanager.getflow.findAutoSelectEntry
import androidx.credentials.CreateCredentialRequest.DisplayInfo
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.GetPublicKeyCredentialOption
import com.android.credentialmanager.common.ProviderActivityState

import java.time.Instant

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
) {
    val requestInfo: RequestInfo
    private val providerEnabledList: List<ProviderData>
    private val providerDisabledList: List<DisabledProviderData>?
    // TODO: require non-null.
    val resultReceiver: ResultReceiver?

    var initialUiState: UiState

    init {
        requestInfo = intent.extras?.getParcelable(
            RequestInfo.EXTRA_REQUEST_INFO,
            RequestInfo::class.java
        ) ?: testGetRequestInfo()

        val originName: String? = when (requestInfo.type) {
            RequestInfo.TYPE_CREATE -> requestInfo.createCredentialRequest?.origin
            RequestInfo.TYPE_GET -> requestInfo.getCredentialRequest?.origin
            else -> null
        }

        providerEnabledList = when (requestInfo.type) {
            RequestInfo.TYPE_CREATE ->
                intent.extras?.getParcelableArrayList(
                    ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
                    CreateCredentialProviderData::class.java
                ) ?: testCreateCredentialEnabledProviderList()
            RequestInfo.TYPE_GET ->
                intent.extras?.getParcelableArrayList(
                    ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
                    GetCredentialProviderData::class.java
                ) ?: testGetCredentialProviderList()
            else -> {
                throw IllegalStateException("Unrecognized request type: ${requestInfo.type}")
            }
        }

        providerDisabledList =
            intent.extras?.getParcelableArrayList(
                ProviderData.EXTRA_DISABLED_PROVIDER_DATA_LIST,
                DisabledProviderData::class.java
            ) ?: testDisabledProviderList()

        resultReceiver = intent.getParcelableExtra(
            Constants.EXTRA_RESULT_RECEIVER,
            ResultReceiver::class.java
        )

        val cancellationRequest = getCancelUiRequest(intent)
        val cancelUiRequestState = cancellationRequest?.let {
            CancelUiRequestState(getAppLabel(context.getPackageManager(), it.appPackageName))
        }

        initialUiState = when (requestInfo.type) {
            RequestInfo.TYPE_CREATE -> {
                val defaultProviderId = userConfigRepo.getDefaultProviderId()
                val isPasskeyFirstUse = userConfigRepo.getIsPasskeyFirstUse()
                val providerEnableListUiState = getCreateProviderEnableListInitialUiState()
                val providerDisableListUiState = getCreateProviderDisableListInitialUiState()
                val requestDisplayInfoUiState =
                    getCreateRequestDisplayInfoInitialUiState(originName)!!
                UiState(
                    createCredentialUiState = CreateFlowUtils.toCreateCredentialUiState(
                        providerEnableListUiState,
                        providerDisableListUiState,
                        defaultProviderId,
                        requestDisplayInfoUiState,
                        isOnPasskeyIntroStateAlready = false,
                        isPasskeyFirstUse
                    )!!,
                    getCredentialUiState = null,
                    cancelRequestState = cancelUiRequestState
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
                    cancelRequestState = cancelUiRequestState
                )
            }
            else -> throw IllegalStateException("Unrecognized request type: ${requestInfo.type}")
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
        onCancel(BaseDialogResult.RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION)
    }

    fun onParsingFailureCancel() {
        onCancel(BaseDialogResult.RESULT_CODE_DATA_PARSING_FAILURE)
    }

    fun onCancel(cancelCode: Int) {
        sendCancellationCode(cancelCode, requestInfo.token, resultReceiver)
    }

    fun onOptionSelected(
        providerId: String,
        entryKey: String,
        entrySubkey: String,
        resultCode: Int? = null,
        resultData: Intent? = null,
    ) {
        val userSelectionDialogResult = UserSelectionDialogResult(
            requestInfo.token,
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
        val providerEnabledList = CreateFlowUtils.toEnabledProviderList(
            providerEnabledList as List<CreateCredentialProviderData>, context
        )
        return providerEnabledList
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

    // TODO: below are prototype functionalities. To be removed for productionization.
    private fun testCreateCredentialEnabledProviderList(): List<CreateCredentialProviderData> {
        return listOf(
            CreateCredentialProviderData
                .Builder("io.enpass.app")
                .setSaveEntries(
                    listOf<Entry>(
                        CreateTestUtils.newCreateEntry(
                            context,
                            "key1", "subkey-1", "elisa.beckett@gmail.com",
                            20, 7, 27, Instant.ofEpochSecond(10L),
                            "You can use your passkey on this or other devices. It is saved to " +
                                "the Password Manager for elisa.beckett@gmail.com."
                        ),
                        CreateTestUtils.newCreateEntry(
                            context,
                            "key1", "subkey-2", "elisa.work@google.com",
                            20, 7, 27, Instant.ofEpochSecond(12L),
                            null
                        ),
                    )
                ).setRemoteEntry(
                    CreateTestUtils.newRemoteCreateEntry(context, "key2", "subkey-1")
                ).build(),
            CreateCredentialProviderData
                .Builder("com.dashlane")
                .setSaveEntries(
                    listOf<Entry>(
                        CreateTestUtils.newCreateEntry(
                            context,
                            "key1", "subkey-3", "elisa.beckett@dashlane.com",
                            20, 7, 27, Instant.ofEpochSecond(11L),
                            null
                        ),
                        CreateTestUtils.newCreateEntry(
                            context,
                            "key1", "subkey-4", "elisa.work@dashlane.com",
                            20, 7, 27, Instant.ofEpochSecond(14L),
                            "You can use your passkey on this or other devices. It is saved to " +
                                "the Password Manager for elisa.work@dashlane.com"
                        ),
                    )
                ).build(),
        )
    }

    private fun testDisabledProviderList(): List<DisabledProviderData>? {
        return listOf(
            DisabledProviderData("com.lastpass.lpandroid"),
            DisabledProviderData("com.google.android.youtube")
        )
    }

    private fun testGetCredentialProviderList(): List<GetCredentialProviderData> {
        return listOf(
            GetCredentialProviderData.Builder("io.enpass.app")
                .setCredentialEntries(
                    listOf(
                        GetTestUtils.newPasswordEntry(
                            context, "key1", "subkey-1", "elisa.family@outlook.com", null,
                            Instant.ofEpochSecond(8000L)
                        ),
                        GetTestUtils.newPasskeyEntry(
                            context, "key1", "subkey-1", "elisa.bakery@gmail.com", "Elisa Beckett",
                            null
                        ),
                        GetTestUtils.newPasswordEntry(
                            context, "key1", "subkey-2", "elisa.bakery@gmail.com", null,
                            Instant.ofEpochSecond(10000L)
                        ),
                        GetTestUtils.newPasskeyEntry(
                            context, "key1", "subkey-3", "elisa.family@outlook.com",
                            "Elisa Beckett", Instant.ofEpochSecond(500L)
                        ),
                    )
                ).setAuthenticationEntries(
                    listOf(
                        GetTestUtils.newAuthenticationEntry(
                            context, "key2", "subkey-1", "locked-user1@gmail.com",
                            AuthenticationEntry.STATUS_LOCKED
                        ),
                        GetTestUtils.newAuthenticationEntry(
                            context, "key2", "subkey-2", "locked-user2@gmail.com",
                            AuthenticationEntry.STATUS_UNLOCKED_BUT_EMPTY_MOST_RECENT
                        ),
                    )
                ).setActionChips(
                    listOf(
                        GetTestUtils.newActionEntry(
                            context, "key3", "subkey-1",
                            "Open Google Password Manager", "elisa.beckett@gmail.com"
                        ),
                        GetTestUtils.newActionEntry(
                            context, "key3", "subkey-2",
                            "Open Google Password Manager", "beckett-family@gmail.com"
                        ),
                    )
                ).setRemoteEntry(
                    GetTestUtils.newRemoteCredentialEntry(context, "key4", "subkey-1")
                ).build(),
            GetCredentialProviderData.Builder("com.dashlane")
                .setCredentialEntries(
                    listOf<Entry>(
                        GetTestUtils.newPasswordEntry(
                            context, "key1", "subkey-2", "elisa.family@outlook.com", null,
                            Instant.ofEpochSecond(9000L)
                        ),
                        GetTestUtils.newPasswordEntry(
                            context, "key1", "subkey-3", "elisa.work@outlook.com", null,
                            Instant.ofEpochSecond(11000L)
                        ),
                    )
                ).setAuthenticationEntries(
                     listOf(
                         GetTestUtils.newAuthenticationEntry(
                             context, "key2", "subkey-1", "foo@email.com",
                             AuthenticationEntry.STATUS_UNLOCKED_BUT_EMPTY_LESS_RECENT,
                         )
                     )
                ).setActionChips(
                    listOf(
                        GetTestUtils.newActionEntry(
                            context, "key3", "subkey-1", "Open Enpass",
                            "Manage passwords"
                        ),
                    )
                ).build(),
        )
    }

    private fun testCreatePasskeyRequestInfo(): RequestInfo {
        val request = CreatePublicKeyCredentialRequest(
            "{\"extensions\": {\n" +
                "                     \"webauthn.loc\": true\n" +
                "                   },\n" +
                "                   \"attestation\": \"direct\",\n" +
                "                   \"challenge\":" +
                " \"-rSQHXSQUdaK1N-La5bE-JPt6EVAW4SxX1K_tXhZ_Gk\",\n" +
                "                   \"user\": {\n" +
                "                     \"displayName\": \"testName\",\n" +
                "                     \"name\": \"credManTesting@gmail.com\",\n" +
                "                     \"id\": \"eD4o2KoXLpgegAtnM5cDhhUPvvk2\"\n" +
                "                   },\n" +
                "                   \"excludeCredentials\": [],\n" +
                "                   \"rp\": {\n" +
                "                     \"name\": \"Address Book\",\n" +
                "                     \"id\": \"addressbook-c7876.uc.r.appspot.com\"\n" +
                "                   },\n" +
                "                   \"timeout\": 60000,\n" +
                "                   \"pubKeyCredParams\": [\n" +
                "                     {\n" +
                "                       \"type\": \"public-key\",\n" +
                "                       \"alg\": -7\n" +
                "                     },\n" +
                "                     {\n" +
                "                       \"type\": \"public-key\",\n" +
                "                       \"alg\": -257\n" +
                "                     },\n" +
                "                     {\n" +
                "                       \"type\": \"public-key\",\n" +
                "                       \"alg\": -37\n" +
                "                     }\n" +
                "                   ],\n" +
                "                   \"authenticatorSelection\": {\n" +
                "                     \"residentKey\": \"required\",\n" +
                "                     \"requireResidentKey\": true\n" +
                "                   }}",
            preferImmediatelyAvailableCredentials = true,
        )
        val credentialData = request.credentialData
        return RequestInfo.newCreateRequestInfo(
                Binder(),
                CreateCredentialRequest.Builder("androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL",
                credentialData, Bundle())
                        .setIsSystemProviderRequired(false)
                        .setAlwaysSendAppInfoToProvider(true)
                        .build(),
                "com.google.android.youtube"
        )
    }

    private fun testCreatePasswordRequestInfo(): RequestInfo {
        val request = CreatePasswordRequest("beckett-bakert@gmail.com", "password123")
        return RequestInfo.newCreateRequestInfo(
                Binder(),
                CreateCredentialRequest.Builder(TYPE_PASSWORD_CREDENTIAL,
                request.credentialData, request.candidateQueryData)
                        .setIsSystemProviderRequired(false)
                        .setAlwaysSendAppInfoToProvider(true)
                        .build(),
                "com.google.android.youtube"
        )
    }

    private fun testCreateOtherCredentialRequestInfo(): RequestInfo {
        val data = Bundle()
        val displayInfo = DisplayInfo("my-username00", "Joe")
        data.putBundle(
            "androidx.credentials.BUNDLE_KEY_REQUEST_DISPLAY_INFO",
            displayInfo.toBundle()
        )
        return RequestInfo.newCreateRequestInfo(
                Binder(),
                CreateCredentialRequest.Builder("other-sign-ins", data, Bundle())
                        .setIsSystemProviderRequired(false)
                        .setAlwaysSendAppInfoToProvider(true)
                        .build(),
                "com.google.android.youtube"
        )
    }

    private fun testGetRequestInfo(): RequestInfo {
        val passwordOption = GetPasswordOption()
        val passkeyOption = GetPublicKeyCredentialOption(
            "json", preferImmediatelyAvailableCredentials = false)
        return RequestInfo.newGetRequestInfo(
            Binder(),
            GetCredentialRequest.Builder(
                Bundle()
            ).addCredentialOption(
                CredentialOption.Builder(
                    passwordOption.type,
                    passwordOption.requestData,
                    passwordOption.candidateQueryData,
                ).setIsSystemProviderRequired(passwordOption.isSystemProviderRequired)
                .build()
            ).addCredentialOption(
                CredentialOption.Builder(
                    passkeyOption.type,
                    passkeyOption.requestData,
                    passkeyOption.candidateQueryData,
                ).setIsSystemProviderRequired(passkeyOption.isSystemProviderRequired)
                .build()
            ).build(),
            "com.google.android.youtube"
        )
    }
}

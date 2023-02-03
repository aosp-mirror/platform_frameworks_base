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

import android.app.slice.Slice
import android.app.slice.SliceSpec
import android.content.Context
import android.content.Intent
import android.credentials.CreateCredentialRequest
import android.credentials.Credential.TYPE_PASSWORD_CREDENTIAL
import android.credentials.CredentialOption
import android.credentials.GetCredentialRequest
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
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ResultReceiver
import com.android.credentialmanager.createflow.DisabledProviderInfo
import com.android.credentialmanager.createflow.EnabledProviderInfo
import com.android.credentialmanager.createflow.RequestDisplayInfo
import com.android.credentialmanager.getflow.GetCredentialUiState
import androidx.credentials.CreateCredentialRequest.DisplayInfo
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePasswordRequest

import java.time.Instant

// Consider repo per screen, similar to view model?
class CredentialManagerRepo(
    private val context: Context,
    intent: Intent,
) {
    val requestInfo: RequestInfo
    private val providerEnabledList: List<ProviderData>
    private val providerDisabledList: List<DisabledProviderData>?

    // TODO: require non-null.
    val resultReceiver: ResultReceiver?

    init {
        requestInfo = intent.extras?.getParcelable(
            RequestInfo.EXTRA_REQUEST_INFO,
            RequestInfo::class.java
        ) ?: testCreatePasskeyRequestInfo()

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
                // TODO: fail gracefully
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
        val resultData = Bundle()
        BaseDialogResult.addToBundle(BaseDialogResult(requestInfo.token), resultData)
        resultReceiver?.send(cancelCode, resultData)
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
        val resultData = Bundle()
        UserSelectionDialogResult.addToBundle(userSelectionDialogResult, resultData)
        resultReceiver?.send(
            BaseDialogResult.RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION,
            resultData
        )
    }

    fun getCredentialInitialUiState(): GetCredentialUiState? {
        val providerEnabledList = GetFlowUtils.toProviderList(
            // TODO: handle runtime cast error
            providerEnabledList as List<GetCredentialProviderData>, context
        )
        val requestDisplayInfo = GetFlowUtils.toRequestDisplayInfo(requestInfo, context)
        return GetCredentialUiState(
            providerEnabledList,
            requestDisplayInfo ?: return null,
        )
    }

    fun getCreateProviderEnableListInitialUiState(): List<EnabledProviderInfo> {
        val providerEnabledList = CreateFlowUtils.toEnabledProviderList(
            // Handle runtime cast error
            providerEnabledList as List<CreateCredentialProviderData>, context
        )
        return providerEnabledList
    }

    fun getCreateProviderDisableListInitialUiState(): List<DisabledProviderInfo> {
        return CreateFlowUtils.toDisabledProviderList(
            // Handle runtime cast error
            providerDisabledList, context
        )
    }

    fun getCreateRequestDisplayInfoInitialUiState(): RequestDisplayInfo? {
        return CreateFlowUtils.toRequestDisplayInfo(requestInfo, context)
    }

    // TODO: below are prototype functionalities. To be removed for productionization.
    private fun testCreateCredentialEnabledProviderList(): List<CreateCredentialProviderData> {
        return listOf(
            CreateCredentialProviderData
                .Builder("io.enpass.app")
                .setSaveEntries(
                    listOf<Entry>(
                        CreateTestUtils.newCreateEntry(context,
                            "key1", "subkey-1", "elisa.beckett@gmail.com",
                            20, 7, 27, Instant.ofEpochSecond(10L),
                            "Legal note"
                        ),
                        CreateTestUtils.newCreateEntry(context,
                            "key1", "subkey-2", "elisa.work@google.com",
                            20, 7, 27, Instant.ofEpochSecond(12L),
                            null
                        ),
                    )
                )
                .setRemoteEntry(
                    newRemoteEntry("key2", "subkey-1")
                )
                .build(),
            CreateCredentialProviderData
                .Builder("com.dashlane")
                .setSaveEntries(
                    listOf<Entry>(
                        CreateTestUtils.newCreateEntry(context,
                            "key1", "subkey-3", "elisa.beckett@dashlane.com",
                            20, 7, 27, Instant.ofEpochSecond(11L),
                            null
                        ),
                        CreateTestUtils.newCreateEntry(context,
                            "key1", "subkey-4", "elisa.work@dashlane.com",
                            20, 7, 27, Instant.ofEpochSecond(14L),
                            null
                        ),
                    )
                )
                .build(),
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
                    listOf<Entry>(
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
                ).setAuthenticationEntry(
                    GetTestUtils.newAuthenticationEntry(context, "key2", "subkey-1")
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
                    newRemoteEntry("key4", "subkey-1")
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
                ).setAuthenticationEntry(
                    GetTestUtils.newAuthenticationEntry(context, "key2", "subkey-1")
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



    private fun newRemoteEntry(
        key: String,
        subkey: String,
    ): Entry {
        return Entry(
            key,
            subkey,
            Slice.Builder(
                Uri.EMPTY, SliceSpec("type", 1)
            ).build()
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
                "                   }}"
        )
        val credentialData = request.credentialData
        return RequestInfo.newCreateRequestInfo(
            Binder(),
            CreateCredentialRequest(
                "androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL",
                credentialData,
                // TODO: populate with actual data
                /*candidateQueryData=*/ Bundle(),
                /*isSystemProviderRequired=*/ false
            ),
            "com.google.android.youtube"
        )
    }

    private fun testCreatePasswordRequestInfo(): RequestInfo {
        val request = CreatePasswordRequest("beckett-bakert@gmail.com", "password123")
        return RequestInfo.newCreateRequestInfo(
            Binder(),
            CreateCredentialRequest(
                TYPE_PASSWORD_CREDENTIAL,
                request.credentialData,
                request.candidateQueryData,
                /*isSystemProviderRequired=*/ false
            ),
            "com.google.android.youtube"
        )
    }

    private fun testCreateOtherCredentialRequestInfo(): RequestInfo {
        val data = Bundle()
        val displayInfo = DisplayInfo("my-username00", "Joe")
        data.putBundle(
            "androidx.credentials.BUNDLE_KEY_REQUEST_DISPLAY_INFO",
            displayInfo.toBundle())
        return RequestInfo.newCreateRequestInfo(
            Binder(),
            CreateCredentialRequest(
                "other-sign-ins",
                data,
                /*candidateQueryData=*/ Bundle(),
                /*isSystemProviderRequired=*/ false
            ),
            "com.google.android.youtube"
        )
    }

    private fun testGetRequestInfo(): RequestInfo {
        return RequestInfo.newGetRequestInfo(
            Binder(),
            GetCredentialRequest.Builder(
                Bundle()
            )
                .addCredentialOption(
                    CredentialOption(
                        "androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL",
                        Bundle(),
                        Bundle(), /*isSystemProviderRequired=*/
                        false
                    )
                )
                .build(),
            "com.google.android.youtube"
        )
    }
}

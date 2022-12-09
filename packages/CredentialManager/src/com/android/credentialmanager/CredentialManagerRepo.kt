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

import android.credentials.Credential.TYPE_PASSWORD_CREDENTIAL
import android.app.PendingIntent
import android.app.slice.Slice
import android.app.slice.SliceSpec
import android.content.Context
import android.content.Intent
import android.credentials.CreateCredentialRequest
import android.credentials.GetCredentialOption
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
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Bundle
import android.os.ResultReceiver
import android.service.credentials.CredentialProviderService
import com.android.credentialmanager.createflow.CreateCredentialUiState
import com.android.credentialmanager.createflow.EnabledProviderInfo
import com.android.credentialmanager.createflow.RemoteInfo
import com.android.credentialmanager.getflow.GetCredentialUiState
import com.android.credentialmanager.getflow.GetScreenState
import com.android.credentialmanager.jetpack.developer.CreatePasswordRequest.Companion.toBundle
import com.android.credentialmanager.jetpack.developer.CreatePublicKeyCredentialRequest
import com.android.credentialmanager.jetpack.developer.PublicKeyCredential.Companion.TYPE_PUBLIC_KEY_CREDENTIAL

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

  fun onCancel() {
    val resultData = Bundle()
    BaseDialogResult.addToBundle(BaseDialogResult(requestInfo.token), resultData)
    resultReceiver?.send(BaseDialogResult.RESULT_CODE_DIALOG_CANCELED, resultData)
  }

  fun onOptionSelected(
    providerPackageName: String,
    entryKey: String,
    entrySubkey: String,
    resultCode: Int? = null,
    resultData: Intent? = null,
  ) {
    val userSelectionDialogResult = UserSelectionDialogResult(
      requestInfo.token,
      providerPackageName,
      entryKey,
      entrySubkey,
      if (resultCode != null) ProviderPendingIntentResponse(resultCode, resultData) else null
    )
    val resultData = Bundle()
    UserSelectionDialogResult.addToBundle(userSelectionDialogResult, resultData)
    resultReceiver?.send(BaseDialogResult.RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION, resultData)
  }

  fun getCredentialInitialUiState(): GetCredentialUiState {
    val providerEnabledList = GetFlowUtils.toProviderList(
    // TODO: handle runtime cast error
      providerEnabledList as List<GetCredentialProviderData>, context)
    // TODO: covert from real requestInfo
    val requestDisplayInfo = com.android.credentialmanager.getflow.RequestDisplayInfo("the app")
    return GetCredentialUiState(
      providerEnabledList,
      GetScreenState.PRIMARY_SELECTION,
      requestDisplayInfo,
    )
  }

  fun createCredentialInitialUiState(): CreateCredentialUiState {
    val requestDisplayInfo = CreateFlowUtils.toRequestDisplayInfo(requestInfo, context)
    val providerEnabledList = CreateFlowUtils.toEnabledProviderList(
      // Handle runtime cast error
      providerEnabledList as List<CreateCredentialProviderData>, requestDisplayInfo, context)
    val providerDisabledList = CreateFlowUtils.toDisabledProviderList(
      // Handle runtime cast error
      providerDisabledList, context)
    var defaultProvider: EnabledProviderInfo? = null
    var remoteEntry: RemoteInfo? = null
    var createOptionSize = 0
    var lastSeenProviderWithNonEmptyCreateOptions: EnabledProviderInfo? = null
    providerEnabledList.forEach{providerInfo -> providerInfo.createOptions =
      providerInfo.createOptions.sortedWith(compareBy { it.lastUsedTimeMillis }).reversed()
      if (providerInfo.isDefault) {defaultProvider = providerInfo}
      if (providerInfo.remoteEntry != null) {
        remoteEntry = providerInfo.remoteEntry!!
      }
      if (providerInfo.createOptions.isNotEmpty()) {
        createOptionSize += providerInfo.createOptions.size
        lastSeenProviderWithNonEmptyCreateOptions = providerInfo
      }
    }
    return CreateCredentialUiState(
      enabledProviders = providerEnabledList,
      disabledProviders = providerDisabledList,
      CreateFlowUtils.toCreateScreenState(
        createOptionSize, false,
        requestDisplayInfo, defaultProvider, remoteEntry),
      requestDisplayInfo,
      false,
      CreateFlowUtils.toActiveEntry(
        /*defaultProvider=*/defaultProvider, createOptionSize,
        lastSeenProviderWithNonEmptyCreateOptions, remoteEntry),
    )
  }

  companion object {
    lateinit var repo: CredentialManagerRepo

    fun setup(
      context: Context,
      intent: Intent,
    ) {
      repo = CredentialManagerRepo(context, intent)
    }

    fun getInstance(): CredentialManagerRepo {
      return repo
    }
  }

  // TODO: below are prototype functionalities. To be removed for productionization.
  private fun testCreateCredentialEnabledProviderList(): List<CreateCredentialProviderData> {
    return listOf(
      CreateCredentialProviderData
        .Builder("io.enpass.app")
        .setSaveEntries(
          listOf<Entry>(
            newCreateEntry("key1", "subkey-1", "elisa.beckett@gmail.com",
              20, 7, 27, 10000),
            newCreateEntry("key1", "subkey-2", "elisa.work@google.com",
              20, 7, 27, 11000),
          )
        )
        .setRemoteEntry(
          newRemoteEntry("key2", "subkey-1")
        )
        .setIsDefaultProvider(true)
        .build(),
      CreateCredentialProviderData
        .Builder("com.dashlane")
        .setSaveEntries(
          listOf<Entry>(
            newCreateEntry("key1", "subkey-3", "elisa.beckett@dashlane.com",
              20, 7, 27, 30000),
            newCreateEntry("key1", "subkey-4", "elisa.work@dashlane.com",
              20, 7, 27, 31000),
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
            newGetEntry(
              "key1", "subkey-1", TYPE_PUBLIC_KEY_CREDENTIAL, "Passkey",
              "elisa.bakery@gmail.com", "Elisa Beckett", 300L
            ),
            newGetEntry(
              "key1", "subkey-2", TYPE_PASSWORD_CREDENTIAL, "Password",
              "elisa.bakery@gmail.com", null, 300L
            ),
            newGetEntry(
              "key1", "subkey-3", TYPE_PASSWORD_CREDENTIAL, "Password",
              "elisa.family@outlook.com", null, 100L
            ),
          )
        ).setAuthenticationEntry(
          newAuthenticationEntry("key2", "subkey-1", TYPE_PASSWORD_CREDENTIAL)
        ).setActionChips(
          listOf(
            newActionEntry(
              "key3", "subkey-1", TYPE_PASSWORD_CREDENTIAL,
              Icon.createWithResource(context, R.drawable.ic_manage_accounts),
              "Open Google Password Manager", "elisa.beckett@gmail.com"
            ),
            newActionEntry(
              "key3", "subkey-2", TYPE_PASSWORD_CREDENTIAL,
              Icon.createWithResource(context, R.drawable.ic_manage_accounts),
              "Open Google Password Manager", "beckett-family@gmail.com"
            ),
          )
        ).setRemoteEntry(
          newRemoteEntry("key4", "subkey-1")
        ).build(),
      GetCredentialProviderData.Builder("com.dashlane")
        .setCredentialEntries(
          listOf<Entry>(
            newGetEntry(
              "key1", "subkey-1", TYPE_PASSWORD_CREDENTIAL, "Password",
              "elisa.family@outlook.com", null, 600L
            ),
            newGetEntry(
              "key1", "subkey-2", TYPE_PUBLIC_KEY_CREDENTIAL, "Passkey",
              "elisa.family@outlook.com", null, 100L
            ),
          )
        ).setAuthenticationEntry(
          newAuthenticationEntry("key2", "subkey-1", TYPE_PASSWORD_CREDENTIAL)
        ).setActionChips(
          listOf(
            newActionEntry(
              "key3", "subkey-1", TYPE_PASSWORD_CREDENTIAL,
              Icon.createWithResource(context, R.drawable.ic_face),
              "Open Enpass"
            ),
          )
        ).build(),
    )
  }

  private fun newActionEntry(
    key: String,
    subkey: String,
    credentialType: String,
    icon: Icon,
    text: String,
    subtext: String? = null,
  ): Entry {
    val slice = Slice.Builder(
      Entry.CREDENTIAL_MANAGER_ENTRY_URI, SliceSpec(credentialType, 1)
    ).addText(
      text, null, listOf(Entry.HINT_ACTION_TITLE)
    ).addIcon(icon, null, listOf(Entry.HINT_ACTION_ICON))
    if (subtext != null) {
      slice.addText(subtext, null, listOf(Entry.HINT_ACTION_SUBTEXT))
    }
    return Entry(
      key,
      subkey,
      slice.build()
    )
  }

  private fun newAuthenticationEntry(
    key: String,
    subkey: String,
    credentialType: String,
  ): Entry {
    val slice = Slice.Builder(
      Entry.CREDENTIAL_MANAGER_ENTRY_URI, SliceSpec(credentialType, 1)
    )
    return Entry(
      key,
      subkey,
      slice.build()
    )
  }

  private fun newGetEntry(
    key: String,
    subkey: String,
    credentialType: String,
    credentialTypeDisplayName: String,
    userName: String,
    userDisplayName: String?,
    lastUsedTimeMillis: Long?,
  ): Entry {
    val intent = Intent("com.androidauth.androidvault.CONFIRM_PASSWORD")
      .setPackage("com.androidauth.androidvault")
    intent.putExtra("provider_extra_sample", "testprovider")

    val pendingIntent = PendingIntent.getActivity(context, 1,
      intent, (PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
              or PendingIntent.FLAG_ONE_SHOT))

    val slice = Slice.Builder(
      Entry.CREDENTIAL_MANAGER_ENTRY_URI, SliceSpec(credentialType, 1)
    ).addText(
      credentialTypeDisplayName, null, listOf(Entry.HINT_CREDENTIAL_TYPE_DISPLAY_NAME)
    ).addText(
      userName, null, listOf(Entry.HINT_USER_NAME)
    ).addIcon(
      Icon.createWithResource(context, R.drawable.ic_passkey),
      null,
      listOf(Entry.HINT_PROFILE_ICON))
    if (userDisplayName != null) {
      slice.addText(userDisplayName, null, listOf(Entry.HINT_PASSKEY_USER_DISPLAY_NAME))
    }
    if (lastUsedTimeMillis != null) {
      slice.addLong(lastUsedTimeMillis, null, listOf(Entry.HINT_LAST_USED_TIME_MILLIS))
    }
    return Entry(
      key,
      subkey,
      slice.build(),
      pendingIntent,
      null
    )
  }

  private fun newCreateEntry(
    key: String,
    subkey: String,
    providerDisplayName: String,
    passwordCount: Int,
    passkeyCount: Int,
    totalCredentialCount: Int,
    lastUsedTimeMillis: Long,
  ): Entry {
    val intent = Intent("com.androidauth.androidvault.CONFIRM_PASSWORD")
      .setPackage("com.androidauth.androidvault")
    intent.putExtra("provider_extra_sample", "testprovider")
    val pendingIntent = PendingIntent.getActivity(context, 1,
      intent, (PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
              or PendingIntent.FLAG_ONE_SHOT))
    val createPasswordRequest = android.service.credentials.CreateCredentialRequest(
            context.applicationInfo.packageName,
            TYPE_PASSWORD_CREDENTIAL,
            toBundle("beckett-bakert@gmail.com", "password123")
    )
    val fillInIntent = Intent().putExtra(CredentialProviderService.EXTRA_CREATE_CREDENTIAL_REQUEST,
            createPasswordRequest)

    val slice = Slice.Builder(
      Entry.CREDENTIAL_MANAGER_ENTRY_URI, SliceSpec(Entry.VERSION, 1)
    ).addText(
        providerDisplayName, null, listOf(Entry.HINT_USER_PROVIDER_ACCOUNT_NAME))
      .addIcon(
        Icon.createWithResource(context, R.drawable.ic_passkey),
        null,
        listOf(Entry.HINT_CREDENTIAL_TYPE_ICON))
      .addIcon(
        Icon.createWithResource(context, R.drawable.ic_profile),
        null,
        listOf(Entry.HINT_PROFILE_ICON))
      .addInt(
        passwordCount, null, listOf(Entry.HINT_PASSWORD_COUNT))
      .addInt(
        passkeyCount, null, listOf(Entry.HINT_PASSKEY_COUNT))
      .addInt(
        totalCredentialCount, null, listOf(Entry.HINT_TOTAL_CREDENTIAL_COUNT))
      .addLong(lastUsedTimeMillis, null, listOf(Entry.HINT_LAST_USED_TIME_MILLIS))
      .build()
    return Entry(
      key,
      subkey,
      slice,
      pendingIntent,
      fillInIntent,
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
        Entry.CREDENTIAL_MANAGER_ENTRY_URI, SliceSpec(Entry.VERSION, 1)
      ).build()
    )
  }

  private fun testCreatePasskeyRequestInfo(): RequestInfo {
    val request = CreatePublicKeyCredentialRequest("{\"extensions\": {\n" +
            "                     \"webauthn.loc\": true\n" +
            "                   },\n" +
            "                   \"attestation\": \"direct\",\n" +
            "                   \"challenge\": \"-rSQHXSQUdaK1N-La5bE-JPt6EVAW4SxX1K_tXhZ_Gk\",\n" +
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
            "                   }}")
    val credentialData = request.data
    return RequestInfo.newCreateRequestInfo(
      Binder(),
      CreateCredentialRequest(
        TYPE_PUBLIC_KEY_CREDENTIAL,
        credentialData,
        // TODO: populate with actual data
        /*candidateQueryData=*/ Bundle(),
        /*requireSystemProvider=*/ false
      ),
      /*isFirstUsage=*/false,
      "tribank"
    )
  }

  private fun testCreatePasswordRequestInfo(): RequestInfo {
    val data = toBundle("beckett-bakert@gmail.com", "password123")
    return RequestInfo.newCreateRequestInfo(
      Binder(),
      CreateCredentialRequest(
        TYPE_PASSWORD_CREDENTIAL,
        data,
        // TODO: populate with actual data
        /*candidateQueryData=*/ Bundle(),
        /*requireSystemProvider=*/ false
      ),
      /*isFirstUsage=*/false,
      "tribank"
    )
  }

  private fun testCreateOtherCredentialRequestInfo(): RequestInfo {
    val data = Bundle()
    return RequestInfo.newCreateRequestInfo(
      Binder(),
      CreateCredentialRequest(
        "other-sign-ins",
        data,
        /*candidateQueryData=*/ Bundle(),
        /*requireSystemProvider=*/ false
      ),
      /*isFirstUsage=*/false,
      "tribank"
    )
  }

  private fun testGetRequestInfo(): RequestInfo {
    return RequestInfo.newGetRequestInfo(
      Binder(),
      GetCredentialRequest.Builder()
        .addGetCredentialOption(
          GetCredentialOption(
            TYPE_PUBLIC_KEY_CREDENTIAL, Bundle(), Bundle(), /*requireSystemProvider=*/ false)
        )
        .build(),
      /*isFirstUsage=*/false,
      "tribank.us"
    )
  }
}

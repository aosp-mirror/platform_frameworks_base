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
import android.credentials.ui.UserSelectionDialogResult
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Bundle
import android.os.ResultReceiver
import com.android.credentialmanager.createflow.ActiveEntry
import com.android.credentialmanager.createflow.CreatePasskeyUiState
import com.android.credentialmanager.createflow.CreateScreenState
import com.android.credentialmanager.createflow.EnabledProviderInfo
import com.android.credentialmanager.createflow.RequestDisplayInfo
import com.android.credentialmanager.getflow.GetCredentialUiState
import com.android.credentialmanager.getflow.GetScreenState
import com.android.credentialmanager.jetpack.developer.PublicKeyCredential.Companion.TYPE_PUBLIC_KEY_CREDENTIAL

// Consider repo per screen, similar to view model?
class CredentialManagerRepo(
  private val context: Context,
  intent: Intent,
) {
  private val requestInfo: RequestInfo
  private val providerEnabledList: List<ProviderData>
  private val providerDisabledList: List<DisabledProviderData>
  // TODO: require non-null.
  val resultReceiver: ResultReceiver?

  init {
    requestInfo = intent.extras?.getParcelable(
      RequestInfo.EXTRA_REQUEST_INFO,
      RequestInfo::class.java
    ) ?: testCreateRequestInfo()

    providerEnabledList = when (requestInfo.type) {
      RequestInfo.TYPE_CREATE ->
        intent.extras?.getParcelableArrayList(
                ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
                CreateCredentialProviderData::class.java
        ) ?: testCreateCredentialEnabledProviderList()
      RequestInfo.TYPE_GET ->
        intent.extras?.getParcelableArrayList(
          ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
          DisabledProviderData::class.java
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

  fun onOptionSelected(providerPackageName: String, entryKey: String, entrySubkey: String) {
    val userSelectionDialogResult = UserSelectionDialogResult(
      requestInfo.token,
      providerPackageName,
      entryKey,
      entrySubkey
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
    val requestDisplayInfo = com.android.credentialmanager.getflow.RequestDisplayInfo("tribank")
    return GetCredentialUiState(
      providerEnabledList,
      GetScreenState.PRIMARY_SELECTION,
      requestDisplayInfo,
    )
  }

  fun createPasskeyInitialUiState(): CreatePasskeyUiState {
    val providerEnabledList = CreateFlowUtils.toEnabledProviderList(
      // Handle runtime cast error
      providerEnabledList as List<CreateCredentialProviderData>, context)
    val providerDisabledList = CreateFlowUtils.toDisabledProviderList(
      // Handle runtime cast error
      providerDisabledList as List<DisabledProviderData>, context)
    var hasDefault = false
    var defaultProvider: EnabledProviderInfo = providerEnabledList.first()
    providerEnabledList.forEach{providerInfo -> providerInfo.createOptions =
      providerInfo.createOptions.sortedWith(compareBy { it.lastUsedTimeMillis }).reversed()
      if (providerInfo.isDefault) {hasDefault = true; defaultProvider = providerInfo} }
    // TODO: covert from real requestInfo
    val requestDisplayInfo = RequestDisplayInfo(
      "Elisa Beckett",
      "beckett-bakert@gmail.com",
      TYPE_PUBLIC_KEY_CREDENTIAL,
      "tribank")
    return CreatePasskeyUiState(
      enabledProviders = providerEnabledList,
      disabledProviders = providerDisabledList,
      if (hasDefault)
      {CreateScreenState.CREATION_OPTION_SELECTION} else {CreateScreenState.PASSKEY_INTRO},
      requestDisplayInfo,
      if (hasDefault) {
        ActiveEntry(defaultProvider, defaultProvider.createOptions.first())
      } else null
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
        .Builder("com.google/com.google.CredentialManagerService")
        .setSaveEntries(
          listOf<Entry>(
            newCreateEntry("key1", "subkey-1", "elisa.beckett@gmail.com",
              20, 7, 27, 10000),
            newCreateEntry("key1", "subkey-2", "elisa.work@google.com",
              20, 7, 27, 11000),
          )
        )
        .setIsDefaultProvider(true)
        .build(),
      CreateCredentialProviderData
        .Builder("com.dashlane/com.dashlane.CredentialManagerService")
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

  private fun testDisabledProviderList(): List<DisabledProviderData> {
    return listOf(
      DisabledProviderData("LastPass"),
      DisabledProviderData("Xyzinstalledbutdisabled"),
    )
  }

  private fun testGetCredentialProviderList(): List<GetCredentialProviderData> {
    return listOf(
      GetCredentialProviderData.Builder("com.google/com.google.CredentialManagerService")
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
        ).build(),
      GetCredentialProviderData.Builder("com.dashlane/com.dashlane.CredentialManagerService")
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
        ).build(),
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
      slice.build()
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
    val slice = Slice.Builder(
      Entry.CREDENTIAL_MANAGER_ENTRY_URI, SliceSpec(Entry.VERSION, 1)
    )
      .addText(
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
      slice
    )
  }

  private fun testCreateRequestInfo(): RequestInfo {
    val data = Bundle()
    return RequestInfo.newCreateRequestInfo(
      Binder(),
      CreateCredentialRequest(
        TYPE_PUBLIC_KEY_CREDENTIAL,
        data
      ),
      /*isFirstUsage=*/false,
      "tribank.us"
    )
  }

  private fun testGetRequestInfo(): RequestInfo {
    val data = Bundle()
    return RequestInfo.newGetRequestInfo(
      Binder(),
      GetCredentialRequest.Builder()
        .addGetCredentialOption(
          GetCredentialOption(TYPE_PUBLIC_KEY_CREDENTIAL, Bundle())
        )
        .build(),
      /*isFirstUsage=*/false,
      "tribank.us"
    )
  }
}

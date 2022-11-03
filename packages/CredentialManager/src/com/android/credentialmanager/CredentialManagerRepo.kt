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
import android.credentials.ui.Constants
import android.credentials.ui.Entry
import android.credentials.ui.CreateCredentialProviderData
import android.credentials.ui.GetCredentialProviderData
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
import com.android.credentialmanager.createflow.ProviderInfo
import com.android.credentialmanager.createflow.RequestDisplayInfo
import com.android.credentialmanager.getflow.GetCredentialUiState
import com.android.credentialmanager.getflow.GetScreenState
import com.android.credentialmanager.jetpack.provider.CredentialEntryUi.Companion.TYPE_PUBLIC_KEY_CREDENTIAL

// Consider repo per screen, similar to view model?
class CredentialManagerRepo(
  private val context: Context,
  intent: Intent,
) {
  private val requestInfo: RequestInfo
  private val providerList: List<ProviderData>
  // TODO: require non-null.
  val resultReceiver: ResultReceiver?

  init {
    requestInfo = intent.extras?.getParcelable(
      RequestInfo.EXTRA_REQUEST_INFO,
      RequestInfo::class.java
    ) ?: testRequestInfo()

    providerList = when (requestInfo.type) {
      RequestInfo.TYPE_CREATE ->
        intent.extras?.getParcelableArrayList(
                ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
                CreateCredentialProviderData::class.java
        ) ?: testCreateCredentialProviderList()
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
    val providerList = GetFlowUtils.toProviderList(
      // TODO: handle runtime cast error
      providerList as List<GetCredentialProviderData>, context)
    // TODO: covert from real requestInfo
    val requestDisplayInfo = com.android.credentialmanager.getflow.RequestDisplayInfo(
      "Elisa Beckett",
      "beckett-bakert@gmail.com",
      TYPE_PUBLIC_KEY_CREDENTIAL,
      "tribank")
    return GetCredentialUiState(
      providerList,
      GetScreenState.CREDENTIAL_SELECTION,
      requestDisplayInfo,
      providerList.first()
    )
  }

  fun createPasskeyInitialUiState(): CreatePasskeyUiState {
    val providerList = CreateFlowUtils.toProviderList(
      // Handle runtime cast error
      providerList as List<CreateCredentialProviderData>, context)
    var hasDefault = false
    var defaultProvider: ProviderInfo = providerList.first()
    providerList.forEach{providerInfo ->
      if (providerInfo.isDefault) {hasDefault = true; defaultProvider = providerInfo} }
    // TODO: covert from real requestInfo
    val requestDisplayInfo = RequestDisplayInfo(
      "Elisa Beckett",
      "beckett-bakert@gmail.com",
      TYPE_PUBLIC_KEY_CREDENTIAL,
      "tribank")
    return CreatePasskeyUiState(
      providers = providerList,
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
  private fun testCreateCredentialProviderList(): List<CreateCredentialProviderData> {
    return listOf(
      CreateCredentialProviderData
        .Builder("com.google/com.google.CredentialManagerService")
        .setSaveEntries(
          listOf<Entry>(
            newEntry("key1", "subkey-1", "elisa.beckett@gmail.com",
              20, 7, 27, 10000),
            newEntry("key1", "subkey-2", "elisa.work@google.com",
              20, 7, 27, 11000),
          )
        )
        .setIsDefaultProvider(true)
        .build(),
      CreateCredentialProviderData
        .Builder("com.dashlane/com.dashlane.CredentialManagerService")
        .setSaveEntries(
          listOf<Entry>(
            newEntry("key1", "subkey-3", "elisa.beckett@dashlane.com",
              20, 7, 27, 30000),
            newEntry("key1", "subkey-4", "elisa.work@dashlane.com",
              20, 7, 27, 31000),
          )
        )
        .build(),
    )
  }

  private fun testGetCredentialProviderList(): List<GetCredentialProviderData> {
    return listOf(
      GetCredentialProviderData.Builder("com.google/com.google.CredentialManagerService")
        .setCredentialEntries(
          listOf<Entry>(
            newEntry("key1", "subkey-1", "elisa.beckett@gmail.com",
              20, 7, 27, 10000),
            newEntry("key1", "subkey-2", "elisa.work@google.com",
              20, 7, 27, 11000),
          )
        ).setActionChips(
          listOf<Entry>(
            newEntry("key2", "subkey-1", "Go to Settings",
              20, 7, 27, 20000),
            newEntry("key2", "subkey-2", "Switch Account",
              20, 7, 27, 21000),
          ),
        ).build(),
      GetCredentialProviderData.Builder("com.dashlane/com.dashlane.CredentialManagerService")
        .setCredentialEntries(
          listOf<Entry>(
            newEntry("key1", "subkey-3", "elisa.beckett@dashlane.com",
              20, 7, 27, 30000),
            newEntry("key1", "subkey-4", "elisa.work@dashlane.com",
              20, 7, 27, 31000),
          )
        ).setActionChips(
          listOf<Entry>(
            newEntry("key2", "subkey-3", "Manage Accounts",
              20, 7, 27, 40000),
          ),
        ).build(),
    )
  }

  private fun newEntry(
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

  private fun testRequestInfo(): RequestInfo {
    val data = Bundle()
    return RequestInfo.newCreateRequestInfo(
      Binder(),
      CreateCredentialRequest(
        // TODO: use the jetpack type and utils once defined.
        TYPE_PUBLIC_KEY_CREDENTIAL,
        data
      ),
      /*isFirstUsage=*/false,
      "tribank.us"
    )
  }
}

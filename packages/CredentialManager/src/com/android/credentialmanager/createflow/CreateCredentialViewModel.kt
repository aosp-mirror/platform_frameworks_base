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

import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.android.credentialmanager.CredentialManagerRepo
import com.android.credentialmanager.common.DialogResult
import com.android.credentialmanager.common.ProviderActivityResult
import com.android.credentialmanager.common.ResultState

data class CreateCredentialUiState(
  val enabledProviders: List<EnabledProviderInfo>,
  val disabledProviders: List<DisabledProviderInfo>? = null,
  val currentScreenState: CreateScreenState,
  val requestDisplayInfo: RequestDisplayInfo,
  val activeEntry: ActiveEntry? = null,
)

class CreateCredentialViewModel(
  credManRepo: CredentialManagerRepo = CredentialManagerRepo.getInstance()
) : ViewModel() {

  var uiState by mutableStateOf(credManRepo.createCredentialInitialUiState())
    private set

  val dialogResult: MutableLiveData<DialogResult> by lazy {
    MutableLiveData<DialogResult>()
  }

  fun observeDialogResult(): LiveData<DialogResult> {
    return dialogResult
  }

  fun onConfirmIntro() {
    if (uiState.enabledProviders.size > 1) {
      uiState = uiState.copy(
        currentScreenState = CreateScreenState.PROVIDER_SELECTION
      )
    } else if (uiState.enabledProviders.size == 1){
      uiState = uiState.copy(
        currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
        activeEntry = ActiveEntry(uiState.enabledProviders.first(),
          uiState.enabledProviders.first().createOptions.first()
        )
      )
    } else {
      throw java.lang.IllegalStateException("Empty provider list.")
    }
  }

  fun onProviderSelected(providerName: String) {
    uiState = uiState.copy(
      currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
      activeEntry = ActiveEntry(getProviderInfoByName(providerName),
        getProviderInfoByName(providerName).createOptions.first()
      )
    )
  }

  fun getProviderInfoByName(providerName: String): EnabledProviderInfo {
    return uiState.enabledProviders.single {
      it.name.equals(providerName)
    }
  }

  fun onMoreOptionsSelected() {
    uiState = uiState.copy(
      currentScreenState = CreateScreenState.MORE_OPTIONS_SELECTION,
    )
  }

  fun onBackButtonSelected() {
    uiState = uiState.copy(
        currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
    )
  }

  fun onMoreOptionsRowSelected(activeEntry: ActiveEntry) {
    uiState = uiState.copy(
      currentScreenState = CreateScreenState.MORE_OPTIONS_ROW_INTRO,
      activeEntry = activeEntry
    )
  }

  fun onDisabledPasswordManagerSelected() {
    // TODO: Complete this function
  }

  fun onRemoteEntrySelected() {
    // TODO: Complete this function
  }

  fun onCancel() {
    CredentialManagerRepo.getInstance().onCancel()
    dialogResult.value = DialogResult(ResultState.CANCELED)
  }

  fun onDefaultOrNotSelected() {
    uiState = uiState.copy(
      currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
    )
    // TODO: implement the if choose as default or not logic later
  }

  fun onPrimaryCreateOptionInfoSelected(
    launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>
  ) {
    val selectedEntry = uiState.activeEntry?.activeEntryInfo
    if (selectedEntry != null) {
      val entryKey = selectedEntry.entryKey
      val entrySubkey = selectedEntry.entrySubkey
      Log.d(
        "Account Selector",
        "Option selected for creation: " +
                "{key = $entryKey, subkey = $entrySubkey}"
      )
      if (selectedEntry.pendingIntent != null) {
        val intentSenderRequest = IntentSenderRequest.Builder(selectedEntry.pendingIntent)
          .setFillInIntent(selectedEntry.fillInIntent).build()
        launcher.launch(intentSenderRequest)
      } else {
        CredentialManagerRepo.getInstance().onOptionSelected(
          uiState.activeEntry?.activeProvider!!.name,
          entryKey,
          entrySubkey
        )
        dialogResult.value = DialogResult(
          ResultState.COMPLETE,
        )
      }
    } else {
      dialogResult.value = DialogResult(
        ResultState.COMPLETE,
      )
      TODO("Gracefully handle illegal state.")
    }
  }

  fun onProviderActivityResult(providerActivityResult: ProviderActivityResult) {
    val entry = uiState.activeEntry?.activeEntryInfo
    val resultCode = providerActivityResult.resultCode
    val resultData = providerActivityResult.data
    val providerId = uiState.activeEntry?.activeProvider!!.name
    if (entry != null) {
      Log.d("Account Selector", "Got provider activity result: {provider=" +
              "$providerId, key=${entry.entryKey}, subkey=${entry.entrySubkey}, " +
              "resultCode=$resultCode, resultData=$resultData}"
      )
      CredentialManagerRepo.getInstance().onOptionSelected(
        providerId, entry.entryKey, entry.entrySubkey, resultCode, resultData,
      )
    } else {
      Log.w("Account Selector",
        "Illegal state: received a provider result but found no matching entry.")
    }
    dialogResult.value = DialogResult(
      ResultState.COMPLETE,
    )
  }
}

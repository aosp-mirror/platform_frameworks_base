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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.android.credentialmanager.CredentialManagerRepo
import com.android.credentialmanager.common.DialogResult
import com.android.credentialmanager.common.ResultState

data class CreatePasskeyUiState(
  val providers: List<ProviderInfo>,
  val currentScreenState: CreateScreenState,
  val selectedProvider: ProviderInfo? = null,
)

class CreatePasskeyViewModel(
  credManRepo: CredentialManagerRepo = CredentialManagerRepo.getInstance()
) : ViewModel() {

  var uiState by mutableStateOf(credManRepo.createPasskeyInitialUiState())
    private set

  val dialogResult: MutableLiveData<DialogResult> by lazy {
    MutableLiveData<DialogResult>()
  }

  fun observeDialogResult(): LiveData<DialogResult> {
    return dialogResult
  }

  fun onConfirmIntro() {
    if (uiState.providers.size > 1) {
      uiState = uiState.copy(
        currentScreenState = CreateScreenState.PROVIDER_SELECTION
      )
    } else if (uiState.providers.size == 1){
      uiState = uiState.copy(
        currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
        selectedProvider = uiState.providers.first()
      )
    } else {
      throw java.lang.IllegalStateException("Empty provider list.")
    }
  }

  fun onProviderSelected(providerName: String) {
    uiState = uiState.copy(
      currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
      selectedProvider = getProviderInfoByName(providerName)
    )
  }

  fun onCreateOptionSelected(createOptionId: Int) {
    Log.d("Account Selector", "Option selected for creation: $createOptionId")
    CredentialManagerRepo.getInstance().onOptionSelected(
      uiState.selectedProvider!!.name,
      createOptionId
    )
    dialogResult.value = DialogResult(
      ResultState.COMPLETE,
    )
  }

  fun getProviderInfoByName(providerName: String): ProviderInfo {
    return uiState.providers.single {
      it.name.equals(providerName)
    }
  }

  fun onMoreOptionsSelected(providerName: String) {
    uiState = uiState.copy(
        currentScreenState = CreateScreenState.MORE_OPTIONS_SELECTION,
        selectedProvider = getProviderInfoByName(providerName)
    )
  }

  fun onBackButtonSelected(providerName: String) {
    uiState = uiState.copy(
        currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
        selectedProvider = getProviderInfoByName(providerName)
    )
  }

  fun onMoreOptionsRowSelected(providerName: String) {
    uiState = uiState.copy(
      currentScreenState = CreateScreenState.MORE_OPTIONS_ROW_INTRO,
      selectedProvider = getProviderInfoByName(providerName)
    )
  }

  fun onCancel() {
    CredentialManagerRepo.getInstance().onCancel()
    dialogResult.value = DialogResult(ResultState.CANCELED)
  }

  fun onDefaultOrNotSelected(providerName: String) {
    uiState = uiState.copy(
      currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
      selectedProvider = getProviderInfoByName(providerName)
    )
    // TODO: implement the if choose as default or not logic later
  }
}

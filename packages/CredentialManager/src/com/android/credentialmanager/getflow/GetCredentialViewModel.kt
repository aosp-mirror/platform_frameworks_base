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

package com.android.credentialmanager.getflow

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

data class GetCredentialUiState(
  val providers: List<ProviderInfo>,
  val currentScreenState: GetScreenState,
  val requestDisplayInfo: RequestDisplayInfo,
  val selectedProvider: ProviderInfo? = null,
)

class GetCredentialViewModel(
  credManRepo: CredentialManagerRepo = CredentialManagerRepo.getInstance()
) : ViewModel() {

  var uiState by mutableStateOf(credManRepo.getCredentialInitialUiState())
      private set

  val dialogResult: MutableLiveData<DialogResult> by lazy {
    MutableLiveData<DialogResult>()
  }

  fun observeDialogResult(): LiveData<DialogResult> {
    return dialogResult
  }

  fun onCredentailSelected(entryKey: String, entrySubkey: String) {
    Log.d("Account Selector", "credential selected: {key=$entryKey,subkey=$entrySubkey}")
    CredentialManagerRepo.getInstance().onOptionSelected(
      uiState.selectedProvider!!.name,
      entryKey,
      entrySubkey
    )
    dialogResult.value = DialogResult(
      ResultState.COMPLETE,
    )
  }

  fun onMoreOptionSelected() {
    Log.d("Account Selector", "More Option selected")
  }

  fun onCancel() {
    CredentialManagerRepo.getInstance().onCancel()
    dialogResult.value = DialogResult(ResultState.CANCELED)
  }
}

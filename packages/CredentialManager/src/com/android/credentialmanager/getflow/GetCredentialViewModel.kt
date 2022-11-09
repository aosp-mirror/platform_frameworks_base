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
import com.android.credentialmanager.jetpack.developer.PublicKeyCredential

data class GetCredentialUiState(
  val providerInfoList: List<ProviderInfo>,
  val currentScreenState: GetScreenState,
  val requestDisplayInfo: RequestDisplayInfo,
  /**
   * The credential entries grouped by userName, derived from all entries of the [providerInfoList].
   * Note that the list order matters to the display order.
   */
  val sortedUserNameToCredentialEntryList: List<PerUserNameCredentialEntryList> =
    createSortedUserNameToCredentialEntryList(providerInfoList),
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

  fun onEntrySelected(entry: EntryInfo) {
    Log.d("Account Selector", "credential selected:" +
            " {provider=${entry.providerId}, key=${entry.entryKey}, subkey=${entry.entrySubkey}}")
    CredentialManagerRepo.getInstance().onOptionSelected(
      entry.providerId,
      entry.entryKey,
      entry.entrySubkey
    )
    dialogResult.value = DialogResult(ResultState.COMPLETE)
  }

  fun onMoreOptionSelected() {
    Log.d("Account Selector", "More Option selected")
    uiState = uiState.copy(
      currentScreenState = GetScreenState.ALL_SIGN_IN_OPTIONS
    )
  }

  fun onBackToPrimarySelectionScreen() {
    uiState = uiState.copy(
      currentScreenState = GetScreenState.PRIMARY_SELECTION
    )
  }

  fun onCancel() {
    CredentialManagerRepo.getInstance().onCancel()
    dialogResult.value = DialogResult(ResultState.CANCELED)
  }
}

internal fun createSortedUserNameToCredentialEntryList(
  providerInfoList: List<ProviderInfo>
): List<PerUserNameCredentialEntryList> {
  // Group by username
  val userNameToEntryMap = mutableMapOf<String, MutableList<CredentialEntryInfo>>()
  providerInfoList.forEach { providerInfo ->
    providerInfo.credentialEntryList.forEach {
      userNameToEntryMap.compute(
        it.userName
      ) {
          _, v ->
        if (v == null) {
          mutableListOf(it)
        } else {
          v.add(it)
          v
        }
      }
    }
  }
  val comparator = CredentialEntryInfoComparator()
  // Sort per username
  userNameToEntryMap.values.forEach {
    it.sortWith(comparator)
  }
  // Transform to list of PerUserNameCredentialEntryLists and then sort across usernames
  return userNameToEntryMap.map {
    PerUserNameCredentialEntryList(it.key, it.value)
  }.sortedWith(
    compareBy(comparator) { it.sortedCredentialEntryList.first() }
  )
}

internal class CredentialEntryInfoComparator : Comparator<CredentialEntryInfo> {
  override fun compare(p0: CredentialEntryInfo, p1: CredentialEntryInfo): Int {
    // First order by last used timestamp
    if (p0.lastUsedTimeMillis != null && p1.lastUsedTimeMillis != null) {
      if (p0.lastUsedTimeMillis < p1.lastUsedTimeMillis) {
        return 1
      } else if (p0.lastUsedTimeMillis > p1.lastUsedTimeMillis) {
        return -1
      }
    } else if (p0.lastUsedTimeMillis != null && p0.lastUsedTimeMillis > 0) {
      return -1
    } else if (p1.lastUsedTimeMillis != null && p1.lastUsedTimeMillis > 0) {
      return 1
    }

    // Then prefer passkey type for its security benefits
    if (p0.credentialType != p1.credentialType) {
      if (PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL == p0.credentialType) {
        return -1
      } else if (PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL == p1.credentialType) {
        return 1
      }
    }
    return 0
  }
}
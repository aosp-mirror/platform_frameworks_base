package com.android.credentialmanager.getflow

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.android.credentialmanager.CredentialManagerRepo

data class GetCredentialUiState(
  val providers: List<ProviderInfo>,
  val currentScreenState: GetScreenState,
  val selectedProvider: ProviderInfo? = null,
)

class GetCredentialViewModel(
  credManRepo: CredentialManagerRepo = CredentialManagerRepo.getInstance()
) : ViewModel() {

  var uiState by mutableStateOf(credManRepo.getCredentialInitialUiState())
      private set

  fun onCredentailSelected(credentialId: String) {
    Log.d("Account Selector", "credential selected: $credentialId")
  }

  fun onMoreOptionSelected() {
    Log.d("Account Selector", "More Option selected")
  }
}

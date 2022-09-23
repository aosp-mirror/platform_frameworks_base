package com.android.credentialmanager.createflow

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.android.credentialmanager.CredentialManagerRepo

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

  fun onCreateOptionSelected(createOptionId: String) {
    Log.d("Account Selector", "Option selected for creation: $createOptionId")
  }

  fun getProviderInfoByName(providerName: String): ProviderInfo {
    return uiState.providers.single {
      it.name.equals(providerName)
    }
  }

  fun onMoreOptionSelected() {
    Log.d("Account Selector", "On more option selected")
  }
}

package com.android.credentialmanager.getflow

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import com.android.credentialmanager.CredentialManagerRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GetCredentialUiState(
  val providers: List<ProviderInfo>
)

class GetCredentialViewModel(
  credManRepo: CredentialManagerRepo
) : ViewModel() {

  private val _uiState = MutableStateFlow(
    GetCredentialUiState(credManRepo.getCredentialProviderList())
  )
  val uiState: StateFlow<GetCredentialUiState> = _uiState.asStateFlow()

  fun getDefaultProviderInfo(): ProviderInfo {
    // TODO: correctly get the default provider.
    return uiState.value.providers.first()
  }

  fun onCredentailSelected(credentialId: String, navController: NavController) {
    Log.d("Account Selector", "credential selected: $credentialId")
  }

  fun onMoreOptionSelected(navController: NavController) {
    Log.d("Account Selector", "More Option selected")
  }
}

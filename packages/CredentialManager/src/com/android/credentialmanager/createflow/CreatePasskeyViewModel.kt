package com.android.credentialmanager.createflow

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import com.android.credentialmanager.CredentialManagerRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CreatePasskeyUiState(
  val providerList: ProviderList,
)

class CreatePasskeyViewModel(
  credManRepo: CredentialManagerRepo
) : ViewModel() {

  private val _uiState = MutableStateFlow(
    CreatePasskeyUiState(credManRepo.createCredentialProviderList())
  )
  val uiState: StateFlow<CreatePasskeyUiState> = _uiState.asStateFlow()

  fun onConfirm(navController: NavController) {
    if (uiState.value.providerList.providers.size > 1) {
      navController.navigate("providerSelection")
    } else if (uiState.value.providerList.providers.size == 1) {
      onProviderSelected(uiState.value.providerList.providers[0].name, navController)
    } else {
      throw java.lang.IllegalStateException("Empty provider list.")
    }
  }

  fun onProviderSelected(providerName: String, navController: NavController) {
    return navController.navigate("createCredentialSelection/$providerName")
  }

  fun onCreateOptionSelected(createOptionId: String) {
    Log.d("Account Selector", "Option selected for creation: $createOptionId")
  }

  fun getProviderInfoByName(providerName: String): ProviderInfo {
    return uiState.value.providerList.providers.single {
      it.name.equals(providerName)
    }
  }

  fun onMoreOptionSelected(navController: NavController) {
    navController.navigate("moreOption")
  }
}

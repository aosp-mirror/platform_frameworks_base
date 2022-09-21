package com.android.credentialmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.android.credentialmanager.createflow.CreatePasskeyViewModel
import com.android.credentialmanager.createflow.createPasskeyGraph
import com.android.credentialmanager.getflow.GetCredentialViewModel
import com.android.credentialmanager.getflow.getCredentialsGraph
import com.android.credentialmanager.ui.theme.CredentialSelectorTheme

@ExperimentalMaterialApi
class CredentialSelectorActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    CredentialManagerRepo.setup(this)
    val startDestination = intent.extras?.getString(
      "start_destination",
      "getCredentials"
    ) ?: "getCredentials"

    setContent {
      CredentialSelectorTheme {
        AppNavHost(
          startDestination = startDestination,
          onCancel = {this.finish()}
        )
      }
    }
  }

  @ExperimentalMaterialApi
  @Composable
  fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String,
    onCancel: () -> Unit,
  ) {
    NavHost(
      modifier = modifier,
      navController = navController,
      startDestination = startDestination
    ) {
      createPasskeyGraph(
        navController = navController,
        viewModel = CreatePasskeyViewModel(CredentialManagerRepo.repo),
        onCancel = onCancel
      )
      getCredentialsGraph(
        navController = navController,
        viewModel = GetCredentialViewModel(CredentialManagerRepo.repo),
        onCancel = onCancel
      )
    }
  }
}

package com.android.credentialmanager

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.Composable
import com.android.credentialmanager.common.DialogType
import com.android.credentialmanager.createflow.CreatePasskeyScreen
import com.android.credentialmanager.getflow.GetCredentialScreen
import com.android.credentialmanager.ui.theme.CredentialSelectorTheme

@ExperimentalMaterialApi
class CredentialSelectorActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    CredentialManagerRepo.setup(this)
    val startDestination = intent.extras?.getString(
      "start_destination",
      "CREATE_PASSKEY"
    ) ?: "CREATE_PASSKEY"

    setContent {
      CredentialSelectorTheme {
        CredentialManagerBottomSheet(startDestination)
      }
    }
  }

  @ExperimentalMaterialApi
  @Composable
  fun CredentialManagerBottomSheet(operationType: String) {
    val dialogType = DialogType.toDialogType(operationType)
    when (dialogType) {
      DialogType.CREATE_PASSKEY -> {
        CreatePasskeyScreen(cancelActivity = onCancel)
      }
      DialogType.GET_CREDENTIALS -> {
        GetCredentialScreen(cancelActivity = onCancel)
      }
      else -> {
        Log.w("AccountSelector", "Unknown type, not rendering any UI")
        this.finish()
      }
    }
  }

  private val onCancel = {
    this@CredentialSelectorActivity.finish()
  }
}

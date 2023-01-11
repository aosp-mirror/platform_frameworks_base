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

package com.android.credentialmanager

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.credentialmanager.common.DialogType
import com.android.credentialmanager.common.DialogResult
import com.android.credentialmanager.common.ProviderActivityResult
import com.android.credentialmanager.common.ResultState
import com.android.credentialmanager.createflow.CreateCredentialScreen
import com.android.credentialmanager.createflow.CreateCredentialViewModel
import com.android.credentialmanager.getflow.GetCredentialScreen
import com.android.credentialmanager.getflow.GetCredentialViewModel
import com.android.credentialmanager.ui.theme.CredentialSelectorTheme
import kotlinx.coroutines.launch

@ExperimentalMaterialApi
class CredentialSelectorActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val credManRepo = CredentialManagerRepo(this, intent)
    UserConfigRepo.setup(this)
    val requestInfo = credManRepo.requestInfo
    setContent {
      CredentialSelectorTheme {
        CredentialManagerBottomSheet(DialogType.toDialogType(requestInfo.type), credManRepo)
      }
    }
  }

  @ExperimentalMaterialApi
  @Composable
  fun CredentialManagerBottomSheet(dialogType: DialogType, credManRepo: CredentialManagerRepo) {
    val providerActivityResult = remember { mutableStateOf<ProviderActivityResult?>(null) }
    val launcher = rememberLauncherForActivityResult(
      ActivityResultContracts.StartIntentSenderForResult()
    ) {
      providerActivityResult.value = ProviderActivityResult(it.resultCode, it.data)
    }
    when (dialogType) {
      DialogType.CREATE_PASSKEY -> {
        val viewModel: CreateCredentialViewModel = viewModel{
          CreateCredentialViewModel(credManRepo)
        }
        lifecycleScope.launch {
          viewModel.observeDialogResult().collect{ dialogResult ->
            onCancel(dialogResult)
          }
        }
        providerActivityResult.value?.let {
          viewModel.onProviderActivityResult(it)
          providerActivityResult.value = null
        }
        CreateCredentialScreen(viewModel = viewModel, providerActivityLauncher = launcher)
      }
      DialogType.GET_CREDENTIALS -> {
        val viewModel: GetCredentialViewModel = viewModel{
          GetCredentialViewModel(credManRepo)
        }
        lifecycleScope.launch {
          viewModel.observeDialogResult().collect{ dialogResult ->
            onCancel(dialogResult)
          }
        }
        providerActivityResult.value?.let {
          viewModel.onProviderActivityResult(it)
          providerActivityResult.value = null
        }
        GetCredentialScreen(viewModel = viewModel, providerActivityLauncher = launcher)
      }
      else -> {
        Log.w("AccountSelector", "Unknown type, not rendering any UI")
        this.finish()
      }
    }
  }

  private fun onCancel(dialogResut: DialogResult) {
    if (dialogResut.resultState == ResultState
        .COMPLETE || dialogResut.resultState == ResultState.NORMAL_CANCELED) {
      this@CredentialSelectorActivity.finish()
    } else if (dialogResut.resultState == ResultState.LAUNCH_SETTING_CANCELED) {
      this@CredentialSelectorActivity.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))
      this@CredentialSelectorActivity.finish()
    }
  }
}

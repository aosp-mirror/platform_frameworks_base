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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.credentialmanager.R
import com.android.credentialmanager.common.material.ModalBottomSheetLayout
import com.android.credentialmanager.common.material.ModalBottomSheetValue
import com.android.credentialmanager.common.material.rememberModalBottomSheetState
import com.android.credentialmanager.createflow.CancelButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GetCredentialScreen(
  viewModel: GetCredentialViewModel,
) {
  val state = rememberModalBottomSheetState(
    initialValue = ModalBottomSheetValue.Expanded,
    skipHalfExpanded = true
  )
  ModalBottomSheetLayout(
    sheetState = state,
    sheetContent = {
      val uiState = viewModel.uiState
      when (uiState.currentScreenState) {
        GetScreenState.CREDENTIAL_SELECTION -> CredentialSelectionCard(
          requestDisplayInfo = uiState.requestDisplayInfo,
          providerInfo = uiState.selectedProvider!!,
          onCancel = viewModel::onCancel,
          onOptionSelected = viewModel::onCredentailSelected,
          multiProvider = uiState.providers.size > 1,
          onMoreOptionSelected = viewModel::onMoreOptionSelected,
        )
      }
    },
    scrimColor = Color.Transparent,
    sheetShape = MaterialTheme.shapes.medium,
  ) {}
  LaunchedEffect(state.currentValue) {
    if (state.currentValue == ModalBottomSheetValue.Hidden) {
      viewModel.onCancel()
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialSelectionCard(
  requestDisplayInfo: RequestDisplayInfo,
  providerInfo: ProviderInfo,
  onOptionSelected: (String, String) -> Unit,
  onCancel: () -> Unit,
  multiProvider: Boolean,
  onMoreOptionSelected: () -> Unit,
) {
  Card() {
    Column() {
      Icon(
        bitmap = providerInfo.credentialTypeIcon.toBitmap().asImageBitmap(),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.align(alignment = Alignment.CenterHorizontally).padding(top = 24.dp)
      )
      Text(
        text = stringResource(R.string.choose_sign_in_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
          .padding(all = 24.dp)
          .align(alignment = Alignment.CenterHorizontally)
      )
      Text(
        text = requestDisplayInfo.appDomainName,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 28.dp)
      )
      Divider(
        thickness = 24.dp,
        color = Color.Transparent
      )
      Card(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
          .padding(horizontal = 24.dp)
          .align(alignment = Alignment.CenterHorizontally)
      ) {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          providerInfo.credentialOptions.forEach {
            item {
              CredentialOptionRow(credentialOptionInfo = it, onOptionSelected = onOptionSelected)
            }
          }
          if (multiProvider) {
            item {
              MoreOptionRow(onSelect = onMoreOptionSelected)
            }
          }
        }
      }
      Divider(
        thickness = 24.dp,
        color = Color.Transparent
      )
      Row(
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
      ) {
        CancelButton(stringResource(R.string.string_no_thanks), onCancel)
      }
      Divider(
        thickness = 18.dp,
        color = Color.Transparent,
        modifier = Modifier.padding(bottom = 16.dp)
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialOptionRow(
    credentialOptionInfo: CredentialOptionInfo,
    onOptionSelected: (String, String) -> Unit,
) {
  SuggestionChip(
    modifier = Modifier.fillMaxWidth(),
    onClick = {onOptionSelected(credentialOptionInfo.entryKey, credentialOptionInfo.entrySubkey)},
    icon = {
      Image(modifier = Modifier.size(24.dp, 24.dp).padding(start = 10.dp),
            bitmap = credentialOptionInfo.icon.toBitmap().asImageBitmap(),
        // TODO: add description.
            contentDescription = "")
    },
    shape = MaterialTheme.shapes.large,
    label = {
      Column() {
        // TODO: fix the text values.
        Text(
          text = credentialOptionInfo.entryKey,
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(top = 16.dp)
        )
        Text(
          text = credentialOptionInfo.entrySubkey,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(bottom = 16.dp)
        )
      }
    }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionRow(onSelect: () -> Unit) {
  SuggestionChip(
    modifier = Modifier.fillMaxWidth().height(52.dp),
    onClick = onSelect,
    shape = MaterialTheme.shapes.large,
    label = {
      Text(
        text = stringResource(R.string.string_more_options),
        style = MaterialTheme.typography.titleLarge,
      )
    }
  )
}

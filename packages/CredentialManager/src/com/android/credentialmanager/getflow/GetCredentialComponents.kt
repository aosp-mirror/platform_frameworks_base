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
import androidx.compose.material.Card
import androidx.compose.material.Chip
import androidx.compose.material.ChipDefaults
import androidx.compose.material.Divider
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.Text
import androidx.compose.material.rememberModalBottomSheetState
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
import com.android.credentialmanager.createflow.CancelButton
import com.android.credentialmanager.ui.theme.Grey100
import com.android.credentialmanager.ui.theme.Shapes
import com.android.credentialmanager.ui.theme.Typography
import com.android.credentialmanager.ui.theme.lightBackgroundColor

@ExperimentalMaterialApi
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
    sheetShape = Shapes.medium,
  ) {}
  LaunchedEffect(state.currentValue) {
    if (state.currentValue == ModalBottomSheetValue.Hidden) {
      viewModel.onCancel()
    }
  }
}

@ExperimentalMaterialApi
@Composable
fun CredentialSelectionCard(
  requestDisplayInfo: RequestDisplayInfo,
  providerInfo: ProviderInfo,
  onOptionSelected: (String, String) -> Unit,
  onCancel: () -> Unit,
  multiProvider: Boolean,
  onMoreOptionSelected: () -> Unit,
) {
  Card(
    backgroundColor = lightBackgroundColor,
  ) {
    Column() {
      Icon(
        bitmap = providerInfo.credentialTypeIcon.toBitmap().asImageBitmap(),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.align(alignment = Alignment.CenterHorizontally).padding(top = 24.dp)
      )
      Text(
        text = stringResource(R.string.choose_sign_in_title),
        style = Typography.subtitle1,
        modifier = Modifier
          .padding(all = 24.dp)
          .align(alignment = Alignment.CenterHorizontally)
      )
      Text(
        text = requestDisplayInfo.appDomainName,
        style = Typography.body2,
        modifier = Modifier.padding(horizontal = 28.dp)
      )
      Divider(
        thickness = 24.dp,
        color = Color.Transparent
      )
      Card(
        shape = Shapes.medium,
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

@ExperimentalMaterialApi
@Composable
fun CredentialOptionRow(
    credentialOptionInfo: CredentialOptionInfo,
    onOptionSelected: (String, String) -> Unit,
) {
  Chip(
    modifier = Modifier.fillMaxWidth(),
    onClick = {onOptionSelected(credentialOptionInfo.entryKey, credentialOptionInfo.entrySubkey)},
    leadingIcon = {
      Image(modifier = Modifier.size(24.dp, 24.dp).padding(start = 10.dp),
            bitmap = credentialOptionInfo.icon.toBitmap().asImageBitmap(),
        // TODO: add description.
            contentDescription = "")
    },
    colors = ChipDefaults.chipColors(
      backgroundColor = Grey100,
      leadingIconContentColor = Grey100
    ),
    shape = Shapes.large
  ) {
    Column() {
      Text(
        text = credentialOptionInfo.entryKey,
        style = Typography.h6,
        modifier = Modifier.padding(top = 16.dp)
      )
      Text(
        text = credentialOptionInfo.entrySubkey,
        style = Typography.body2,
        modifier = Modifier.padding(bottom = 16.dp)
      )
    }
  }
}

@ExperimentalMaterialApi
@Composable
fun MoreOptionRow(onSelect: () -> Unit) {
  Chip(
    modifier = Modifier.fillMaxWidth().height(52.dp),
    onClick = onSelect,
    colors = ChipDefaults.chipColors(
      backgroundColor = Grey100,
      leadingIconContentColor = Grey100
    ),
    shape = Shapes.large
  ) {
    Text(
      text = stringResource(R.string.string_more_options),
      style = Typography.h6,
    )
  }
}

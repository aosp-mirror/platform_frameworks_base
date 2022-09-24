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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.credentialmanager.R
import com.android.credentialmanager.createflow.CancelButton
import com.android.credentialmanager.ui.theme.Grey100
import com.android.credentialmanager.ui.theme.Shapes
import com.android.credentialmanager.ui.theme.Typography
import com.android.credentialmanager.ui.theme.lightBackgroundColor

@ExperimentalMaterialApi
@Composable
fun GetCredentialScreen(
  viewModel: GetCredentialViewModel = viewModel(),
  cancelActivity: () -> Unit,
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
          providerInfo = uiState.selectedProvider!!,
          onCancel = cancelActivity,
          onOptionSelected = {viewModel.onCredentailSelected(it)},
          multiProvider = uiState.providers.size > 1,
          onMoreOptionSelected = {viewModel.onMoreOptionSelected()},
        )
      }
    },
    scrimColor = Color.Transparent,
    sheetShape = Shapes.medium,
  ) {}
  LaunchedEffect(state.currentValue) {
    when (state.currentValue) {
      ModalBottomSheetValue.Hidden -> {
        cancelActivity()
      }
    }
  }
}

@ExperimentalMaterialApi
@Composable
fun CredentialSelectionCard(
  providerInfo: ProviderInfo,
  onOptionSelected: (String) -> Unit,
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
        text = providerInfo.appDomainName,
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
    onOptionSelected: (String) -> Unit
) {
  Chip(
    modifier = Modifier.fillMaxWidth(),
    onClick = {onOptionSelected(credentialOptionInfo.id)},
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
        text = credentialOptionInfo.title,
        style = Typography.h6,
        modifier = Modifier.padding(top = 16.dp)
      )
      Text(
        text = credentialOptionInfo.subtitle,
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

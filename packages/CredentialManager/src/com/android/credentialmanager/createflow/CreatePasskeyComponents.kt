package com.android.credentialmanager.createflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Button
import androidx.compose.material.ButtonColors
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Chip
import androidx.compose.material.ChipDefaults
import androidx.compose.material.Divider
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.credentialmanager.R
import com.android.credentialmanager.ui.theme.Grey100
import com.android.credentialmanager.ui.theme.Shapes
import com.android.credentialmanager.ui.theme.Typography
import com.android.credentialmanager.ui.theme.lightBackgroundColor
import com.android.credentialmanager.ui.theme.lightColorAccentSecondary
import com.android.credentialmanager.ui.theme.lightSurface1

@ExperimentalMaterialApi
@Composable
fun CreatePasskeyScreen(
  viewModel: CreatePasskeyViewModel,
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
        CreateScreenState.PASSKEY_INTRO -> ConfirmationCard(
          onConfirm = {viewModel.onConfirmIntro()},
          onCancel = {viewModel.onCancel()},
        )
        CreateScreenState.PROVIDER_SELECTION -> ProviderSelectionCard(
          providerList = uiState.providers,
          onCancel = {viewModel.onCancel()},
          onProviderSelected = {viewModel.onProviderSelected(it)}
        )
        CreateScreenState.CREATION_OPTION_SELECTION -> CreationSelectionCard(
          providerInfo = uiState.selectedProvider!!,
          onOptionSelected = {viewModel.onCreateOptionSelected(it)},
          onCancel = {viewModel.onCancel()},
          multiProvider = uiState.providers.size > 1,
          onMoreOptionsSelected = {viewModel.onMoreOptionsSelected(it)}
        )
        CreateScreenState.MORE_OPTIONS_SELECTION -> MoreOptionsSelectionCard(
            providerInfo = uiState.selectedProvider!!,
            providerList = uiState.providers,
            onBackButtonSelected = {viewModel.onBackButtonSelected(it)},
            onOptionSelected = {viewModel.onMoreOptionsRowSelected(it)}
          )
        CreateScreenState.MORE_OPTIONS_ROW_INTRO -> MoreOptionsRowIntroCard(
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

@Composable
fun ConfirmationCard(
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
) {
  Card(
    backgroundColor = lightBackgroundColor,
  ) {
    Column() {
      Icon(
        painter = painterResource(R.drawable.ic_passkey),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.align(alignment = Alignment.CenterHorizontally).padding(top = 24.dp)
      )
      Text(
        text = stringResource(R.string.passkey_creation_intro_title),
        style = Typography.subtitle1,
        modifier = Modifier
          .padding(horizontal = 24.dp)
          .align(alignment = Alignment.CenterHorizontally)
      )
      Divider(
        thickness = 24.dp,
        color = Color.Transparent
      )
      Text(
        text = stringResource(R.string.passkey_creation_intro_body),
        style = Typography.body1,
        modifier = Modifier.padding(horizontal = 28.dp)
      )
      Divider(
        thickness = 48.dp,
        color = Color.Transparent
      )
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
      ) {
        CancelButton(
          stringResource(R.string.string_cancel),
          onclick = onCancel
        )
        ConfirmButton(
          stringResource(R.string.string_continue),
          onclick = onConfirm
        )
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
fun ProviderSelectionCard(
  providerList: List<ProviderInfo>,
  onProviderSelected: (String) -> Unit,
  onCancel: () -> Unit
) {
  Card(
    backgroundColor = lightBackgroundColor,
  ) {
    Column() {
      Text(
        text = stringResource(R.string.choose_provider_title),
        style = Typography.subtitle1,
        modifier = Modifier.padding(all = 24.dp).align(alignment = Alignment.CenterHorizontally)
      )
      Text(
        text = stringResource(R.string.choose_provider_body),
        style = Typography.body1,
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
          providerList.forEach {
            item {
              ProviderRow(providerInfo = it, onProviderSelected = onProviderSelected)
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
        CancelButton(stringResource(R.string.string_cancel), onCancel)
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
fun MoreOptionsSelectionCard(
  providerInfo: ProviderInfo,
  providerList: List<ProviderInfo>,
  onBackButtonSelected: (String) -> Unit,
  onOptionSelected: (String) -> Unit
) {
  Card(
    backgroundColor = lightBackgroundColor,
  ) {
    Column() {
      TopAppBar(
        title = {
          Text(text = stringResource(R.string.string_more_options), style = Typography.subtitle1)
        },
        backgroundColor = lightBackgroundColor,
        elevation = 0.dp,
        navigationIcon =
        {
          IconButton(onClick = { onBackButtonSelected(providerInfo.name) }) {
            Icon(Icons.Filled.ArrowBack, "backIcon"
            )
          }
        }
      )
      Divider(
         thickness = 24.dp,
         color = Color.Transparent
      )
      Text(
        text = stringResource(R.string.create_passkey_at),
        style = Typography.body1,
        modifier = Modifier.padding(horizontal = 28.dp),
        textAlign = TextAlign.Center
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
          // TODO: change the order according to usage frequency
          providerList.forEach { providerInfo ->
            providerInfo.createOptions.forEach { createOptionInfo ->
              item {
                MoreOptionsInfoRow(providerInfo = providerInfo,
                  createOptionInfo = createOptionInfo,
                  onOptionSelected = onOptionSelected)
              }
            }
          }
        }
      }
      Divider(
        thickness = 18.dp,
        color = Color.Transparent,
        modifier = Modifier.padding(bottom = 40.dp)
      )
    }
  }
}

@ExperimentalMaterialApi
@Composable
fun MoreOptionsRowIntroCard(
) {
  Card(
    backgroundColor = lightBackgroundColor,
  ) {
  }
}

@ExperimentalMaterialApi
@Composable
fun ProviderRow(providerInfo: ProviderInfo, onProviderSelected: (String) -> Unit) {
  Chip(
    modifier = Modifier.fillMaxWidth(),
    onClick = {onProviderSelected(providerInfo.name)},
    leadingIcon = {
      Image(modifier = Modifier.size(24.dp, 24.dp).padding(start = 10.dp),
            bitmap = providerInfo.icon.toBitmap().asImageBitmap(),
            // painter = painterResource(R.drawable.ic_passkey),
            // TODO: add description.
            contentDescription = "")
    },
    colors = ChipDefaults.chipColors(
      backgroundColor = Grey100,
      leadingIconContentColor = Grey100
    ),
    shape = Shapes.large
  ) {
    Text(
      text = providerInfo.name,
      style = Typography.button,
      modifier = Modifier.padding(vertical = 18.dp)
    )
  }
}

@Composable
fun CancelButton(text: String, onclick: () -> Unit) {
  val colors = ButtonDefaults.buttonColors(
    backgroundColor = lightBackgroundColor
  )
  NavigationButton(
    border = BorderStroke(1.dp, lightSurface1),
    colors = colors,
    text = text,
    onclick = onclick)
}

@Composable
fun ConfirmButton(text: String, onclick: () -> Unit) {
  val colors = ButtonDefaults.buttonColors(
    backgroundColor = lightColorAccentSecondary
  )
  NavigationButton(
    colors = colors,
    text = text,
    onclick = onclick)
}

@Composable
fun NavigationButton(
    border: BorderStroke? = null,
    colors: ButtonColors,
    text: String,
    onclick: () -> Unit
) {
  Button(
    onClick = onclick,
    shape = Shapes.small,
    colors = colors,
    border = border
  ) {
    Text(text = text, style = Typography.button)
  }
}

@ExperimentalMaterialApi
@Composable
fun CreationSelectionCard(
  providerInfo: ProviderInfo,
  onOptionSelected: (Int) -> Unit,
  onCancel: () -> Unit,
  multiProvider: Boolean,
  onMoreOptionsSelected: (String) -> Unit,
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
        text = "${stringResource(R.string.choose_create_option_title)} ${providerInfo.name}",
        style = Typography.subtitle1,
        modifier = Modifier.padding(all = 24.dp).align(alignment = Alignment.CenterHorizontally)
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
          providerInfo.createOptions.forEach {
            item {
              CreateOptionRow(createOptionInfo = it, onOptionSelected = onOptionSelected)
            }
          }
          if (multiProvider) {
            item {
              MoreOptionsRow(onSelect = { onMoreOptionsSelected(providerInfo.name) })
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
        CancelButton(stringResource(R.string.string_cancel), onCancel)
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
fun CreateOptionRow(createOptionInfo: CreateOptionInfo, onOptionSelected: (Int) -> Unit) {
  Chip(
    modifier = Modifier.fillMaxWidth(),
    onClick = {onOptionSelected(createOptionInfo.id)},
    leadingIcon = {
      Image(modifier = Modifier.size(24.dp, 24.dp).padding(start = 10.dp),
        bitmap = createOptionInfo.icon.toBitmap().asImageBitmap(),
            // painter = painterResource(R.drawable.ic_passkey),
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
        text = createOptionInfo.title,
        style = Typography.h6,
        modifier = Modifier.padding(top = 16.dp)
      )
      Text(
        text = createOptionInfo.subtitle,
        style = Typography.body2,
        modifier = Modifier.padding(bottom = 16.dp)
      )
    }
  }
}

@ExperimentalMaterialApi
@Composable
fun MoreOptionsInfoRow(
  providerInfo: ProviderInfo,
  createOptionInfo: CreateOptionInfo,
  onOptionSelected: (String) -> Unit
) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onOptionSelected(providerInfo.name) },
        leadingIcon = {
            Image(modifier = Modifier.size(24.dp, 24.dp).padding(start = 10.dp),
                bitmap = createOptionInfo.icon.toBitmap().asImageBitmap(),
                // painter = painterResource(R.drawable.ic_passkey),
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
                text = if (providerInfo.createOptions.size > 1)
                {providerInfo.name + " for " + createOptionInfo.title} else { providerInfo.name},
                style = Typography.h6,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = createOptionInfo.usageData,
                style = Typography.body2,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@ExperimentalMaterialApi
@Composable
fun MoreOptionsRow(onSelect: () -> Unit) {
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
        text = stringResource(R.string.string_create_at_another_place),
        style = Typography.h6,
      )
  }
}

package com.android.credentialmanager.createflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.TextButton
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
import com.android.credentialmanager.jetpack.provider.CredentialEntryUi.Companion.TYPE_PASSWORD_CREDENTIAL
import com.android.credentialmanager.jetpack.provider.CredentialEntryUi.Companion.TYPE_PUBLIC_KEY_CREDENTIAL
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
          onConfirm = viewModel::onConfirmIntro,
          onCancel = viewModel::onCancel,
        )
        CreateScreenState.PROVIDER_SELECTION -> ProviderSelectionCard(
          providerList = uiState.providers,
          onCancel = viewModel::onCancel,
          onProviderSelected = viewModel::onProviderSelected
        )
        CreateScreenState.CREATION_OPTION_SELECTION -> CreationSelectionCard(
          requestDisplayInfo = uiState.requestDisplayInfo,
          providerInfo = uiState.activeEntry?.activeProvider!!,
          createOptionInfo = uiState.activeEntry.activeEntryInfo as CreateOptionInfo,
          onOptionSelected = viewModel::onPrimaryCreateOptionInfoSelected,
          onConfirm = viewModel::onPrimaryCreateOptionInfoSelected,
          onCancel = viewModel::onCancel,
          multiProvider = uiState.providers.size > 1,
          onMoreOptionsSelected = viewModel::onMoreOptionsSelected
        )
        CreateScreenState.MORE_OPTIONS_SELECTION -> MoreOptionsSelectionCard(
            providerList = uiState.providers,
            onBackButtonSelected = viewModel::onBackButtonSelected,
            onOptionSelected = viewModel::onMoreOptionsRowSelected
          )
        CreateScreenState.MORE_OPTIONS_ROW_INTRO -> MoreOptionsRowIntroCard(
          providerInfo = uiState.activeEntry?.activeProvider!!,
          onDefaultOrNotSelected = viewModel::onDefaultOrNotSelected
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
  providerList: List<ProviderInfo>,
  onBackButtonSelected: () -> Unit,
  onOptionSelected: (ActiveEntry) -> Unit
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
          IconButton(onClick = onBackButtonSelected) {
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
                MoreOptionsInfoRow(
                  providerInfo = providerInfo,
                  createOptionInfo = createOptionInfo,
                  onOptionSelected = {
                    onOptionSelected(ActiveEntry(providerInfo, createOptionInfo))
                  })
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
  providerInfo: ProviderInfo,
  onDefaultOrNotSelected: () -> Unit,
) {
  Card(
    backgroundColor = lightBackgroundColor,
  ) {
    Column() {
      Text(
        text = stringResource(R.string.use_provider_for_all_title, providerInfo.displayName),
        style = Typography.subtitle1,
        modifier = Modifier.padding(all = 24.dp).align(alignment = Alignment.CenterHorizontally)
      )
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
      ) {
        CancelButton(
          stringResource(R.string.use_once),
          onclick = onDefaultOrNotSelected
        )
        ConfirmButton(
          stringResource(R.string.set_as_default),
          onclick = onDefaultOrNotSelected
        )
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
      text = providerInfo.displayName,
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
  requestDisplayInfo: RequestDisplayInfo,
  providerInfo: ProviderInfo,
  createOptionInfo: CreateOptionInfo,
  onOptionSelected: () -> Unit,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
  multiProvider: Boolean,
  onMoreOptionsSelected: () -> Unit,
) {
  Card(
    backgroundColor = lightBackgroundColor,
  ) {
    Column() {
      Icon(
        bitmap = createOptionInfo.credentialTypeIcon.toBitmap().asImageBitmap(),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.align(alignment = Alignment.CenterHorizontally).padding(all = 24.dp)
      )
      Text(
        text = when (requestDisplayInfo.type) {
          TYPE_PUBLIC_KEY_CREDENTIAL -> stringResource(R.string.choose_create_option_passkey_title,
            providerInfo.displayName)
          TYPE_PASSWORD_CREDENTIAL -> stringResource(R.string.choose_create_option_password_title,
            providerInfo.displayName)
          else -> stringResource(R.string.choose_create_option_sign_in_title,
            providerInfo.displayName)
        },
        style = Typography.subtitle1,
        modifier = Modifier.padding(horizontal = 24.dp)
          .align(alignment = Alignment.CenterHorizontally),
        textAlign = TextAlign.Center,
      )
      Text(
        text = requestDisplayInfo.appDomainName,
        style = Typography.body2,
        modifier = Modifier.align(alignment = Alignment.CenterHorizontally)
      )
      Text(
        text = stringResource(
          R.string.choose_create_option_description,
          when (requestDisplayInfo.type) {
            TYPE_PUBLIC_KEY_CREDENTIAL -> stringResource(R.string.passkeys)
            TYPE_PASSWORD_CREDENTIAL -> stringResource(R.string.passwords)
            else -> stringResource(R.string.sign_ins)
          },
          providerInfo.displayName,
          createOptionInfo.userProviderDisplayName),
        style = Typography.body1,
        modifier = Modifier.padding(all = 24.dp).align(alignment = Alignment.CenterHorizontally)
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
            item {
              PrimaryCreateOptionRow(
                requestDisplayInfo = requestDisplayInfo,
                createOptionInfo = createOptionInfo,
                onOptionSelected = onOptionSelected
              )
            }
        }
      }
      if (multiProvider) {
        TextButton(
          onClick = onMoreOptionsSelected,
          modifier = Modifier
          .padding(horizontal = 24.dp)
          .align(alignment = Alignment.CenterHorizontally)){
          Text(
              text =
                when (requestDisplayInfo.type) {
                  TYPE_PUBLIC_KEY_CREDENTIAL ->
                    stringResource(R.string.string_create_in_another_place)
                  else -> stringResource(R.string.string_save_to_another_place)},
            textAlign = TextAlign.Center,
          )
        }
      }
      Divider(
        thickness = 24.dp,
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
fun PrimaryCreateOptionRow(
  requestDisplayInfo: RequestDisplayInfo,
  createOptionInfo: CreateOptionInfo,
  onOptionSelected: () -> Unit
) {
  Chip(
    modifier = Modifier.fillMaxWidth(),
    onClick = onOptionSelected,
    leadingIcon = {
      Image(modifier = Modifier.size(24.dp, 24.dp).padding(start = 10.dp),
        bitmap = createOptionInfo.credentialTypeIcon.toBitmap().asImageBitmap(),
        contentDescription = stringResource(R.string.createOptionInfo_icon_description))
    },
    colors = ChipDefaults.chipColors(
      backgroundColor = Grey100,
      leadingIconContentColor = Grey100
    ),
    shape = Shapes.large
  ) {
    Column() {
      Text(
        text = requestDisplayInfo.userName,
        style = Typography.h6,
        modifier = Modifier.padding(top = 16.dp)
      )
      Text(
        text = requestDisplayInfo.displayName,
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
  onOptionSelected: () -> Unit
) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOptionSelected,
        leadingIcon = {
            Image(modifier = Modifier.size(24.dp, 24.dp).padding(start = 10.dp),
                bitmap = createOptionInfo.profileIcon.toBitmap().asImageBitmap(),
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
                text =
                if (providerInfo.createOptions.size > 1)
                {stringResource(R.string.more_options_title_multiple_options,
                  providerInfo.displayName, createOptionInfo.userProviderDisplayName)} else {
                  stringResource(R.string.more_options_title_one_option,
                    providerInfo.displayName)},
                style = Typography.h6,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = stringResource(R.string.more_options_usage_data,
                  createOptionInfo.passwordCount, createOptionInfo.passkeyCount),
                style = Typography.body2,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}
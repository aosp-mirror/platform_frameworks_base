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
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.Text
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.android.credentialmanager.R
import com.android.credentialmanager.ui.theme.Grey100
import com.android.credentialmanager.ui.theme.Shapes
import com.android.credentialmanager.ui.theme.Typography
import com.android.credentialmanager.ui.theme.lightBackgroundColor
import com.android.credentialmanager.ui.theme.lightColorAccentSecondary
import com.android.credentialmanager.ui.theme.lightSurface1

@ExperimentalMaterialApi
fun NavGraphBuilder.createPasskeyGraph(
  navController: NavController,
  viewModel: CreatePasskeyViewModel,
  onCancel: () -> Unit,
  startDestination: String = "intro", // TODO: get this from view model
) {
  navigation(startDestination = startDestination, route = "createPasskey") {
    composable("intro") {
      CreatePasskeyIntroDialog(
        onCancel = onCancel,
        onConfirm = {viewModel.onConfirm(navController)}
      )
    }
    composable("providerSelection") {
        ProviderSelectionDialog(
          providerList = viewModel.uiState.collectAsState().value.providerList,
          onProviderSelected = {viewModel.onProviderSelected(it, navController)},
          onCancel = onCancel
        )
    }
    composable(
      "createCredentialSelection/{providerName}",
      arguments = listOf(navArgument("providerName") {type = NavType.StringType})
    ) {
      val arguments = it.arguments
      if (arguments == null) {
        throw java.lang.IllegalStateException("createCredentialSelection without a provider name")
      }
      CreationSelectionDialog(
        providerInfo = viewModel.getProviderInfoByName(arguments.getString("providerName")!!),
        onOptionSelected = {viewModel.onCreateOptionSelected(it)},
        onCancel = onCancel,
        multiProvider = viewModel.uiState.collectAsState().value.providerList.providers.size > 1,
        onMoreOptionSelected = {viewModel.onMoreOptionSelected(navController)},
      )
    }
  }
}

/**
 * BEGIN INTRO CONTENT
 */
@ExperimentalMaterialApi
@Composable
fun CreatePasskeyIntroDialog(
  onCancel: () -> Unit = {},
  onConfirm: () -> Unit = {},
) {
  val state = rememberModalBottomSheetState(
    initialValue = ModalBottomSheetValue.Expanded,
    skipHalfExpanded = true
  )
  ModalBottomSheetLayout(
    sheetState = state,
    sheetContent = {
      ConfirmationCard(
        onCancel = onCancel, onConfirm = onConfirm
      )
    },
    scrimColor = Color.Transparent,
    sheetShape = Shapes.medium,
  ) {}
  LaunchedEffect(state.currentValue) {
    when (state.currentValue) {
      ModalBottomSheetValue.Hidden -> {
        onCancel()
      }
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

/**
 * END INTRO CONTENT
 */

/**
 * BEGIN PROVIDER SELECTION CONTENT
 */
@ExperimentalMaterialApi
@Composable
fun ProviderSelectionDialog(
  providerList: ProviderList,
  onProviderSelected: (String) -> Unit,
  onCancel: () -> Unit,
) {
  val state = rememberModalBottomSheetState(
    initialValue = ModalBottomSheetValue.Expanded,
    skipHalfExpanded = true
  )
  ModalBottomSheetLayout(
    sheetState = state,
    sheetContent = {
      ProviderSelectionCard(
        providerList = providerList,
        onCancel = onCancel,
        onProviderSelected = onProviderSelected
      )
    },
    scrimColor = Color.Transparent,
    sheetShape = Shapes.medium,
  ) {}
  LaunchedEffect(state.currentValue) {
    when (state.currentValue) {
      ModalBottomSheetValue.Hidden -> {
        onCancel()
      }
    }
  }
}

@ExperimentalMaterialApi
@Composable
fun ProviderSelectionCard(
  providerList: ProviderList,
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
          providerList.providers.forEach {
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

/**
 * END PROVIDER SELECTION CONTENT
 */

/**
 * BEGIN COMMON COMPONENTS
 */

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

/**
 * BEGIN CREATE OPTION SELECTION CONTENT
 */
@ExperimentalMaterialApi
@Composable
fun CreationSelectionDialog(
  providerInfo: ProviderInfo,
  onOptionSelected: (String) -> Unit,
  onCancel: () -> Unit,
  multiProvider: Boolean,
  onMoreOptionSelected: () -> Unit,
) {
  val state = rememberModalBottomSheetState(
    initialValue = ModalBottomSheetValue.Expanded,
    skipHalfExpanded = true
  )
  ModalBottomSheetLayout(
    sheetState = state,
    sheetContent = {
      CreationSelectionCard(
        providerInfo = providerInfo,
        onCancel = onCancel,
        onOptionSelected = onOptionSelected,
        multiProvider = multiProvider,
        onMoreOptionSelected = onMoreOptionSelected,
      )
    },
    scrimColor = Color.Transparent,
    sheetShape = Shapes.medium,
  ) {}
  LaunchedEffect(state.currentValue) {
    when (state.currentValue) {
      ModalBottomSheetValue.Hidden -> {
        onCancel()
      }
    }
  }
}

@ExperimentalMaterialApi
@Composable
fun CreationSelectionCard(
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
fun CreateOptionRow(createOptionInfo: CreateOptionInfo, onOptionSelected: (String) -> Unit) {
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
/**
 * END CREATE OPTION SELECTION CONTENT
 */

/**
 * END COMMON COMPONENTS
 */

@ExperimentalMaterialApi
@Preview(showBackground = true)
@Composable
fun CreatePasskeyEntryScreenPreview() {
  // val providers = ProviderList(
  //   listOf(
  //     ProviderInfo(null),
  //     ProviderInfo(null, "Dashlane"),
  //     ProviderInfo(null, "LastPass")
  //   )
  // )
  // TatiAccountSelectorTheme {
  //   ConfirmationCard({}, {})
  // }
}

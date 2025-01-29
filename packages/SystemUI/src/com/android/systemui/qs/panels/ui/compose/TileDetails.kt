/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.systemui.bluetooth.qsdialog.BluetoothDetailsContent
import com.android.systemui.bluetooth.qsdialog.BluetoothDetailsViewModel
import com.android.systemui.plugins.qs.TileDetailsViewModel
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.tiles.dialog.InternetDetailsContent
import com.android.systemui.qs.tiles.dialog.InternetDetailsViewModel
import com.android.systemui.qs.tiles.dialog.ModesDetailsContent
import com.android.systemui.qs.tiles.dialog.ModesDetailsViewModel
import com.android.systemui.qs.tiles.dialog.ScreenRecordDetailsContent
import com.android.systemui.qs.tiles.dialog.ScreenRecordDetailsViewModel

@Composable
fun TileDetails(modifier: Modifier = Modifier, detailsViewModel: DetailsViewModel) {

    if (!QsDetailedView.isEnabled) {
        throw IllegalStateException("QsDetailedView should be enabled")
    }

    val tileDetailedViewModel = detailsViewModel.activeTileDetails ?: return

    DisposableEffect(Unit) { onDispose { detailsViewModel.closeDetailedView() } }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                // The height of the details view is TBD.
                .fillMaxHeight()
    ) {
        CompositionLocalProvider(
            value = LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { detailsViewModel.closeDetailedView() },
                    modifier =
                        Modifier.align(Alignment.CenterVertically)
                            .height(TileDetailsDefaults.IconHeight)
                            .padding(start = TileDetailsDefaults.IconPadding),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        // Description is TBD
                        contentDescription = "Back to QS panel",
                    )
                }
                Text(
                    text = tileDetailedViewModel.getTitle(),
                    modifier = Modifier.align(Alignment.CenterVertically),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(
                    onClick = { tileDetailedViewModel.clickOnSettingsButton() },
                    modifier =
                        Modifier.align(Alignment.CenterVertically)
                            .height(TileDetailsDefaults.IconHeight)
                            .padding(end = TileDetailsDefaults.IconPadding),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        // Description is TBD
                        contentDescription = "Go to Settings",
                    )
                }
            }
            Text(
                text = tileDetailedViewModel.getSubTitle(),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        MapTileDetailsContent(tileDetailedViewModel)
    }
}

@Composable
private fun MapTileDetailsContent(tileDetailsViewModel: TileDetailsViewModel) {
    when (tileDetailsViewModel) {
        is InternetDetailsViewModel -> InternetDetailsContent(tileDetailsViewModel)
        is ScreenRecordDetailsViewModel -> ScreenRecordDetailsContent(tileDetailsViewModel)
        is BluetoothDetailsViewModel -> BluetoothDetailsContent()
        is ModesDetailsViewModel -> ModesDetailsContent(tileDetailsViewModel)
    }
}

private object TileDetailsDefaults {
    val IconHeight = 48.dp
    val IconPadding = 4.dp
}

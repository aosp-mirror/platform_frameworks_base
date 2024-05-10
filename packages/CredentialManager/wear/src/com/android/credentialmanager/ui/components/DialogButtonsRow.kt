/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.credentialmanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.ButtonDefaults
import com.google.android.horologist.compose.material.Button
import com.google.android.horologist.compose.tools.WearPreview
import com.android.credentialmanager.R
import com.google.android.horologist.annotations.ExperimentalHorologistApi

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun DialogButtonsRow(
    onCancelClick: () -> Unit,
    onOKClick: () -> Unit,
    modifier: Modifier = Modifier,
    cancelButtonIcon: ImageVector = Icons.Default.Close,
    okButtonIcon: ImageVector = Icons.Default.Check,
    cancelButtonContentDescription: String = stringResource(R.string.dialog_cancel_button_cd),
    okButtonContentDescription: String = stringResource(R.string.dialog_ok_button_cd),
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
    ) {
        Button(
            imageVector = cancelButtonIcon,
            contentDescription = cancelButtonContentDescription,
            onClick = onCancelClick,
            colors = ButtonDefaults.secondaryButtonColors(),
        )
        Button(
            imageVector = okButtonIcon,
            contentDescription = okButtonContentDescription,
            onClick = onOKClick,
            modifier = Modifier.padding(start = 20.dp)
        )
    }
}

@WearPreview
@Composable
fun DialogButtonsRowPreview() {
    DialogButtonsRow(onCancelClick = {}, onOKClick = {})
}


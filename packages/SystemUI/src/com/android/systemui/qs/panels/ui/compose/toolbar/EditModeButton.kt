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

package com.android.systemui.qs.panels.ui.compose.toolbar

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.EditModeButtonViewModel
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R

@Composable
fun EditModeButton(
    viewModelFactory: EditModeButtonViewModel.Factory,
    modifier: Modifier = Modifier,
) {
    val viewModel = rememberViewModel(traceName = "EditModeButton") { viewModelFactory.create() }
    CompositionLocalProvider(
        value = LocalContentColor provides MaterialTheme.colorScheme.onSurface
    ) {
        IconButton(
            onClick = viewModel::onButtonClick,
            shape = RoundedCornerShape(CornerSize(28.dp)),
            modifier =
                modifier.borderOnFocus(
                    color = MaterialTheme.colorScheme.secondary,
                    cornerSize = CornerSize(24.dp),
                ),
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(id = R.string.qs_edit),
            )
        }
    }
}

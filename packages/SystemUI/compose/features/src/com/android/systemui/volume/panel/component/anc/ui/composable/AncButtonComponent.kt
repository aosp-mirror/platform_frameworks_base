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

package com.android.systemui.volume.panel.component.anc.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.anc.ui.viewmodel.AncViewModel
import com.android.systemui.volume.panel.ui.composable.ComposeVolumePanelUiComponent
import com.android.systemui.volume.panel.ui.composable.VolumePanelComposeScope
import javax.inject.Inject

class AncButtonComponent
@Inject
constructor(
    private val viewModel: AncViewModel,
    private val ancPopup: AncPopup,
) : ComposeVolumePanelUiComponent {

    @Composable
    override fun VolumePanelComposeScope.Content(modifier: Modifier) {
        val slice by viewModel.buttonSlice.collectAsState()
        val label = stringResource(R.string.volume_panel_noise_control_title)
        val isClickable = viewModel.isClickable(slice)
        val onClick =
            if (isClickable) {
                { ancPopup.show(null) }
            } else {
                null
            }
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SliceAndroidView(
                modifier =
                    Modifier.height(64.dp)
                        .fillMaxWidth()
                        .semantics {
                            role = Role.Button
                            contentDescription = label
                        }
                        .clip(RoundedCornerShape(28.dp)),
                slice = slice,
                isEnabled = onClick != null,
                onWidthChanged = viewModel::onButtonSliceWidthChanged,
                onClick = onClick,
            )
            Text(
                modifier = Modifier.clearAndSetSemantics {},
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

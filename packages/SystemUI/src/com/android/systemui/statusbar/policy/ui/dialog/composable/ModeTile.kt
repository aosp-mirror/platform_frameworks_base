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

package com.android.systemui.statusbar.policy.ui.dialog.composable

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.statusbar.policy.ui.dialog.viewmodel.ModeTileViewModel

@Composable
fun ModeTile(viewModel: ModeTileViewModel) {
    val tileColor: Color by
        animateColorAsState(
            if (viewModel.enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant
        )
    val contentColor: Color by
        animateColorAsState(
            if (viewModel.enabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Surface(color = tileColor, shape = RoundedCornerShape(16.dp)) {
            Row(
                modifier =
                    Modifier.combinedClickable(
                            onClick = viewModel.onClick,
                            onLongClick = viewModel.onLongClick,
                            onLongClickLabel = viewModel.onLongClickLabel,
                        )
                        .padding(16.dp)
                        .semantics { stateDescription = viewModel.stateDescription },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement =
                    Arrangement.spacedBy(space = 12.dp, alignment = Alignment.Start),
            ) {
                Icon(icon = viewModel.icon, modifier = Modifier.size(24.dp))
                Column {
                    Text(
                        viewModel.text,
                        fontWeight = FontWeight.W500,
                        modifier = Modifier.tileMarquee().testTag("name"),
                    )
                    Text(
                        viewModel.subtext,
                        fontWeight = FontWeight.W400,
                        modifier =
                            Modifier.tileMarquee()
                                .testTag(if (viewModel.enabled) "stateOn" else "stateOff")
                                .clearAndSetSemantics {
                                    contentDescription = viewModel.subtextDescription
                                },
                    )
                }
            }
        }
    }
}

private fun Modifier.tileMarquee(): Modifier {
    return this.basicMarquee(iterations = 1)
}

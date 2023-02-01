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

package com.android.systemui.people.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.R

@Composable
internal fun PeopleScreenEmpty(
    onGotItClicked: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(PeopleSpacePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.select_conversation_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(50.dp))

        Text(
            stringResource(R.string.no_conversations_text),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))
        ExampleTile()
        Spacer(Modifier.weight(1f))

        val androidColors = LocalAndroidColorScheme.current
        Button(
            onGotItClicked,
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = androidColors.colorAccentPrimary,
                    contentColor = androidColors.textColorOnAccent,
                )
        ) { Text(stringResource(R.string.got_it)) }
    }
}

@Composable
private fun ExampleTile() {
    val androidColors = LocalAndroidColorScheme.current
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = androidColors.colorSurface,
        contentColor = androidColors.textColorPrimary,
    ) {
        Row(
            Modifier.padding(vertical = 20.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // TODO(b/238993727): Add a content description.
                Image(
                    painterResource(R.drawable.ic_avatar_with_badge),
                    contentDescription = null,
                    Modifier.size(40.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.empty_user_name),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(24.dp))

            Text(
                stringResource(R.string.empty_status),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

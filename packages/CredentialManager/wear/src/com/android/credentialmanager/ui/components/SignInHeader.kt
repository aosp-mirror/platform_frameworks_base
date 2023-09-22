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

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.android.credentialmanager.R
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.material.Icon
import com.google.android.horologist.compose.material.util.DECORATIVE_ELEMENT_CONTENT_DESCRIPTION
import com.google.android.horologist.compose.tools.WearPreview

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun SignInHeader(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
) {
    SignInHeader(
        iconContent = {
            Icon(
                id = icon,
                contentDescription = DECORATIVE_ELEMENT_CONTENT_DESCRIPTION
            )
        },
        title = title,
        modifier = modifier,
    )
}

@Composable
fun SignInHeader(
    iconContent: @Composable ColumnScope.() -> Unit,
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        iconContent()
        Text(
            text = title,
            modifier = Modifier
                .padding(top = 6.dp)
                .padding(horizontal = 10.dp),
            style = MaterialTheme.typography.title3
        )
    }
}

@WearPreview
@Composable
fun SignInHeaderPreview() {
    SignInHeader(
        icon = R.drawable.passkey_icon,
        title = stringResource(R.string.use_passkey_title)
    )
}

/*
 * Copyright 2024 The Android Open Source Project
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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.credentialmanager.common.ui.components.WearDisplayNameText
import com.android.credentialmanager.common.ui.components.WearUsernameText
import com.google.android.horologist.compose.tools.WearPreview

@Composable
fun AccountRow(
    primaryText: String,
    secondaryText: String? = null,
) {
    Column(modifier = Modifier.padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        WearDisplayNameText(
            text = primaryText,
        )
        if (secondaryText != null) {
            WearUsernameText(
                text = secondaryText,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@WearPreview
@Composable
fun AccountRowPreview() {
    AccountRow(
        primaryText = "Elisa Beckett",
        secondaryText = "beckett_bakery@gmail.com",
    )
}

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
 *
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.systemui.compose.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.systemui.compose.SysUiButton
import com.android.systemui.compose.SysUiOutlinedButton
import com.android.systemui.compose.SysUiTextButton

@Composable
fun ButtonsScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        SysUiButton(
            onClick = {},
        ) {
            Text("SysUiButton")
        }

        SysUiButton(
            onClick = {},
            enabled = false,
        ) {
            Text("SysUiButton - disabled")
        }

        SysUiOutlinedButton(
            onClick = {},
        ) {
            Text("SysUiOutlinedButton")
        }

        SysUiOutlinedButton(
            onClick = {},
            enabled = false,
        ) {
            Text("SysUiOutlinedButton - disabled")
        }

        SysUiTextButton(
            onClick = {},
        ) {
            Text("SysUiTextButton")
        }

        SysUiTextButton(
            onClick = {},
            enabled = false,
        ) {
            Text("SysUiTextButton - disabled")
        }
    }
}

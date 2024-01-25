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

package com.android.settingslib.spa.widget.scaffold

import androidx.appcompat.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource

/**
 * Scope for the children of [MoreOptionsAction].
 */
abstract class MoreOptionsScope {
    abstract fun dismiss()

    @Composable
    fun MenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(text) },
            onClick = {
                dismiss()
                onClick()
            },
            enabled = enabled,
        )
    }
}

@Composable
fun MoreOptionsAction(
    content: @Composable MoreOptionsScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    MoreOptionsActionButton { expanded = true }
    val onDismiss = { expanded = false }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val moreOptionsScope = remember(this) {
            object : MoreOptionsScope() {
                override fun dismiss() {
                    onDismiss()
                }
            }
        }
        moreOptionsScope.content()
    }
}

@Composable
private fun MoreOptionsActionButton(onClick: () -> Unit) {
    IconButton(onClick) {
        Icon(
            imageVector = Icons.Outlined.MoreVert,
            contentDescription = stringResource(R.string.abc_action_menu_overflow_description),
        )
    }
}

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

package com.android.systemui.qs.tiles.dialog

import android.view.LayoutInflater
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.plugins.qs.TileDetailsViewModel
import com.android.systemui.res.R

class InternetDetailsViewModel(
    onLongClick: () -> Unit,
) : TileDetailsViewModel() {
    private val _onLongClick = onLongClick

    @Composable
    override fun GetContentView() {
        AndroidView(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            factory = { context ->
                // Inflate with the existing dialog xml layout
                LayoutInflater.from(context)
                    .inflate(R.layout.internet_connectivity_dialog, null)
                // TODO: b/377388104 - Implement the internet details view
            },
        )
    }

    override fun clickOnSettingsButton() {
        _onLongClick()
    }

    override fun getTitle(): String {
        // TODO: b/377388104 Update the placeholder text
        return "Internet"
    }

    override fun getSubTitle(): String {
        // TODO: b/377388104 Update the placeholder text
        return "Tab a network to connect"
    }
}

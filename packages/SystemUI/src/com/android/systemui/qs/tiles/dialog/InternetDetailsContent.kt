/*
 * Copyright (C) 2025 The Android Open Source Project
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.res.R

@Composable
fun InternetDetailsContent(viewModel: InternetDetailsViewModel) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val internetDetailsContentManager = remember {
        viewModel.contentManagerFactory.create(
            canConfigMobileData = viewModel.getCanConfigMobileData(),
            canConfigWifi = viewModel.getCanConfigWifi(),
            coroutineScope = coroutineScope,
            context = context,
        )
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            // Inflate with the existing dialog xml layout and bind it with the manager
            val view =
                LayoutInflater.from(context).inflate(R.layout.internet_connectivity_dialog, null)
            internetDetailsContentManager.bind(view)

            view
            // TODO: b/377388104 - Polish the internet details view UI
        },
        onRelease = { internetDetailsContentManager.unBind() },
    )
}

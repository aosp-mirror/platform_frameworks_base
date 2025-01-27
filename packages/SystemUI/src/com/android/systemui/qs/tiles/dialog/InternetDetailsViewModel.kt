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

import android.util.Log
import android.view.LayoutInflater
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.plugins.qs.TileDetailsViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.AccessPointController
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class InternetDetailsViewModel
@AssistedInject
constructor(
    private val accessPointController: AccessPointController,
    private val contentManagerFactory: InternetDetailsContentManager.Factory,
    @Assisted private val onLongClick: () -> Unit,
) : TileDetailsViewModel() {
    private lateinit var internetDetailsContentManager: InternetDetailsContentManager

    @Composable
    override fun GetContentView() {
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current

        internetDetailsContentManager = remember {
            contentManagerFactory.create(
                canConfigMobileData = accessPointController.canConfigMobileData(),
                canConfigWifi = accessPointController.canConfigWifi(),
                coroutineScope = coroutineScope,
                context = context,
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            factory = { context ->
                // Inflate with the existing dialog xml layout and bind it with the manager
                val view =
                    LayoutInflater.from(context)
                        .inflate(R.layout.internet_connectivity_dialog, null)
                internetDetailsContentManager.bind(view)

                view
                // TODO: b/377388104 - Polish the internet details view UI
            },
            onRelease = {
                internetDetailsContentManager.unBind()
                if (DEBUG) {
                    Log.d(TAG, "onRelease")
                }
            },
        )
    }

    override fun clickOnSettingsButton() {
        onLongClick()
    }

    override fun getTitle(): String {
        // TODO: b/377388104 make title and sub title mutable states of string
        // by internetDetailsContentManager.getTitleText()
        // TODO: test title change between airplane mode and not airplane mode
        // TODO: b/377388104 Update the placeholder text
        return "Internet"
    }

    override fun getSubTitle(): String {
        // TODO: b/377388104 make title and sub title mutable states of string
        // by internetDetailsContentManager.getSubtitleText()
        // TODO: test subtitle change between airplane mode and not airplane mode
        // TODO: b/377388104 Update the placeholder text
        return "Tab a network to connect"
    }

    @AssistedFactory
    interface Factory {
        fun create(onLongClick: () -> Unit): InternetDetailsViewModel
    }

    companion object {
        private const val TAG = "InternetDetailsVModel"
        private val DEBUG: Boolean = Log.isLoggable(TAG, Log.DEBUG)
    }
}

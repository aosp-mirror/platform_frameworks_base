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

import com.android.systemui.plugins.qs.TileDetailsViewModel
import com.android.systemui.statusbar.connectivity.AccessPointController
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class InternetDetailsViewModel
@AssistedInject
constructor(
    private val accessPointController: AccessPointController,
    val contentManagerFactory: InternetDetailsContentManager.Factory,
    @Assisted private val onLongClick: () -> Unit,
) : TileDetailsViewModel() {
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

    fun getCanConfigMobileData(): Boolean {
        return accessPointController.canConfigMobileData()
    }

    fun getCanConfigWifi(): Boolean {
        return accessPointController.canConfigWifi()
    }

    @AssistedFactory
    interface Factory {
        fun create(onLongClick: () -> Unit): InternetDetailsViewModel
    }
}

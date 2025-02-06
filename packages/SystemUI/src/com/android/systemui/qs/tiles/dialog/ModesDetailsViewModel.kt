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

import com.android.systemui.plugins.qs.TileDetailsViewModel
import com.android.systemui.statusbar.policy.ui.dialog.viewmodel.ModesDialogViewModel

/** The view model used for the modes details view in the Quick Settings */
class ModesDetailsViewModel(
    private val onSettingsClick: () -> Unit,
    val viewModel: ModesDialogViewModel,
) : TileDetailsViewModel() {
    override fun clickOnSettingsButton() {
        onSettingsClick()
    }

    override fun getTitle(): String {
        // TODO(b/388321032): Replace this string with a string in a translatable xml file.
        return "Modes"
    }

    override fun getSubTitle(): String {
        // TODO(b/388321032): Replace this string with a string in a translatable xml file.
        return "Silences interruptions from people and apps in different circumstances"
    }
}

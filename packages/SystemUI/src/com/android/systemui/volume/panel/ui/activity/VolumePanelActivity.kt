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

package com.android.systemui.volume.panel.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.volume.panel.shared.flag.VolumePanelFlag
import com.android.systemui.volume.panel.ui.composable.VolumePanelRoot
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelViewModel
import javax.inject.Inject
import javax.inject.Provider

class VolumePanelActivity
@Inject
constructor(
    private val volumePanelViewModelFactory: Provider<VolumePanelViewModel.Factory>,
    private val volumePanelFlag: VolumePanelFlag,
    private val configurationController: ConfigurationController,
) : ComponentActivity() {

    private val viewModel: VolumePanelViewModel by
        viewModels(factoryProducer = { volumePanelViewModelFactory.get() })

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        volumePanelFlag.assertNewVolumePanel()

        setContent { VolumePanelRoot(viewModel = viewModel, onDismiss = ::finish) }
    }

    override fun onContentChanged() {
        super.onContentChanged()
        configurationController.onConfigurationChanged(resources.configuration)
    }
}

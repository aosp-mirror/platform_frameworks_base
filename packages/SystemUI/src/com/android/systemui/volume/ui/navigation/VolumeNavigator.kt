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

package com.android.systemui.volume.ui.navigation

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.createBottomSheet
import com.android.systemui.volume.VolumePanelFactory
import com.android.systemui.volume.domain.model.VolumePanelRoute
import com.android.systemui.volume.panel.ui.VolumePanelUiEvent
import com.android.systemui.volume.panel.ui.composable.VolumePanelRoot
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelViewModel
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class VolumeNavigator
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainContext: CoroutineContext,
    private val volumePanelFactory: VolumePanelFactory,
    private val activityStarter: ActivityStarter,
    private val viewModelFactory: VolumePanelViewModel.Factory,
    private val dialogFactory: SystemUIDialogFactory,
    private val uiEventLogger: UiEventLogger,
) {

    fun openVolumePanel(route: VolumePanelRoute) {
        when (route) {
            VolumePanelRoute.COMPOSE_VOLUME_PANEL -> showNewVolumePanel()
            VolumePanelRoute.SETTINGS_VOLUME_PANEL ->
                activityStarter.startActivity(
                    /* intent= */ Intent(Settings.Panel.ACTION_VOLUME),
                    /* dismissShade= */ true
                )
            VolumePanelRoute.SYSTEM_UI_VOLUME_PANEL ->
                volumePanelFactory.create(aboveStatusBar = true, view = null)
        }
    }

    private fun showNewVolumePanel() {
        applicationScope.launch(mainContext) {
            uiEventLogger.log(VolumePanelUiEvent.VOLUME_PANEL_SHOWN)
            dialogFactory
                .createBottomSheet(
                    content = { dialog ->
                        LaunchedEffect(dialog) {
                            dialog.setOnDismissListener {
                                uiEventLogger.log(VolumePanelUiEvent.VOLUME_PANEL_GONE)
                            }
                        }

                        VolumePanelRoot(
                            viewModel = viewModelFactory.create(rememberCoroutineScope()),
                            onDismiss = { dialog.dismiss() },
                        )
                    },
                )
                .show()
        }
    }
}

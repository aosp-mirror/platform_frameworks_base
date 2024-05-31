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

import android.app.Dialog
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.createBottomSheet
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.android.systemui.volume.VolumePanelFactory
import com.android.systemui.volume.domain.model.VolumePanelRoute
import com.android.systemui.volume.panel.domain.interactor.VolumePanelGlobalStateInteractor
import com.android.systemui.volume.panel.ui.VolumePanelUiEvent
import com.android.systemui.volume.panel.ui.composable.VolumePanelRoot
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelViewModel
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
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
    private val volumePanelGlobalStateInteractor: VolumePanelGlobalStateInteractor,
) {

    init {
        volumePanelGlobalStateInteractor.globalState
            .map { it.isVisible }
            .distinctUntilChanged()
            .flatMapLatest { isVisible ->
                if (isVisible) {
                    conflatedCallbackFlow<Unit> {
                            val dialog = createNewVolumePanelDialog()
                            uiEventLogger.log(VolumePanelUiEvent.VOLUME_PANEL_SHOWN)
                            dialog.show()
                            awaitClose { dialog.dismiss() }
                        }
                        .flowOn(mainContext)
                } else {
                    emptyFlow()
                }
            }
            .launchIn(applicationScope)
    }

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
        activityStarter.dismissKeyguardThenExecute(
            {
                volumePanelGlobalStateInteractor.setVisible(true)
                false
            },
            {},
            true
        )
    }

    private fun createNewVolumePanelDialog(): Dialog {
        return dialogFactory.createBottomSheet(
            content = { dialog ->
                LaunchedEffect(dialog) {
                    dialog.setOnDismissListener {
                        uiEventLogger.log(VolumePanelUiEvent.VOLUME_PANEL_GONE)
                        volumePanelGlobalStateInteractor.setVisible(false)
                    }
                }

                val coroutineScope = rememberCoroutineScope()
                VolumePanelRoot(
                    remember(coroutineScope) { viewModelFactory.create(coroutineScope) }
                )
            },
        )
    }
}

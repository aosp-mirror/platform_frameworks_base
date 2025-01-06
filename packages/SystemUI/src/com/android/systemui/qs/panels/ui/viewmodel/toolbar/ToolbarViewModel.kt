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

package com.android.systemui.qs.panels.ui.viewmodel.toolbar

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.systemui.animation.Expandable
import com.android.systemui.classifier.domain.interactor.FalsingInteractor
import com.android.systemui.classifier.domain.interactor.runIfNotFalseTap
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.qs.footer.domain.interactor.FooterActionsInteractor
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.powerButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.settingsButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.userSwitcherViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class ToolbarViewModel
@AssistedInject
constructor(
    val editModeButtonViewModelFactory: EditModeButtonViewModel.Factory,
    private val footerActionsInteractor: FooterActionsInteractor,
    private val globalActionsDialogLiteProvider: Provider<GlobalActionsDialogLite>,
    private val falsingInteractor: FalsingInteractor,
    @ShadeDisplayAware appContext: Context,
) : ExclusiveActivatable() {
    private val qsThemedContext =
        ContextThemeWrapper(appContext, R.style.Theme_SystemUI_QuickSettings)
    private val hydrator = Hydrator("ToolbarViewModel.hydrator")

    val powerButtonViewModel = powerButtonViewModel(qsThemedContext, ::onPowerButtonClicked)

    val settingsButtonViewModel =
        settingsButtonViewModel(qsThemedContext, ::onSettingsButtonClicked)

    val userSwitcherViewModel: FooterActionsButtonViewModel? by
        hydrator.hydratedStateOf(
            traceName = "userSwitcherViewModel",
            initialValue = null,
            source =
                userSwitcherViewModel(
                    qsThemedContext,
                    footerActionsInteractor,
                    ::onUserSwitcherClicked,
                ),
        )

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch {
                try {
                    globalActionsDialogLite = globalActionsDialogLiteProvider.get()
                    awaitCancellation()
                } finally {
                    globalActionsDialogLite?.destroy()
                }
            }
            launch { hydrator.activate() }
            awaitCancellation()
        }
    }

    private var globalActionsDialogLite: GlobalActionsDialogLite? by mutableStateOf(null)

    private fun onPowerButtonClicked(expandable: Expandable) {
        falsingInteractor.runIfNotFalseTap {
            globalActionsDialogLite?.let {
                footerActionsInteractor.showPowerMenuDialog(it, expandable)
            }
        }
    }

    private fun onUserSwitcherClicked(expandable: Expandable) {
        falsingInteractor.runIfNotFalseTap { footerActionsInteractor.showUserSwitcher(expandable) }
    }

    private fun onSettingsButtonClicked(expandable: Expandable) {
        falsingInteractor.runIfNotFalseTap { footerActionsInteractor.showSettings(expandable) }
    }

    @AssistedFactory
    interface Factory {
        fun create(): ToolbarViewModel
    }
}

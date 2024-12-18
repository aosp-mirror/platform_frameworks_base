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

package com.android.systemui.dreams.homecontrols.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.controls.flags.Flags.homePanelDream
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.Flags.homeControlsDreamHsum
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dreams.homecontrols.HomeControlsDreamService
import com.android.systemui.dreams.homecontrols.system.domain.interactor.HomeControlsComponentInteractor
import com.android.systemui.settings.UserContextProvider
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class HomeControlsDreamStartable
@Inject
constructor(
    context: Context,
    private val systemPackageManager: PackageManager,
    private val userContextProvider: UserContextProvider,
    private val homeControlsComponentInteractor: HomeControlsComponentInteractor,
    @Background private val bgScope: CoroutineScope,
) : CoreStartable {

    private val componentName = ComponentName(context, HomeControlsDreamService::class.java)

    override fun start() {
        bgScope.launch {
            if (homePanelDream()) {
                homeControlsComponentInteractor.panelComponent.collect { selectedPanelComponent ->
                    setEnableHomeControlPanel(selectedPanelComponent != null)
                }
            } else {
                setEnableHomeControlPanel(false)
            }
        }
    }

    private fun setEnableHomeControlPanel(enabled: Boolean) {
        val packageState =
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        val packageManager =
            if (homeControlsDreamHsum()) {
                userContextProvider.userContext.packageManager
            } else {
                systemPackageManager
            }
        packageManager.setComponentEnabledSetting(
            componentName,
            packageState,
            PackageManager.DONT_KILL_APP,
        )
    }
}

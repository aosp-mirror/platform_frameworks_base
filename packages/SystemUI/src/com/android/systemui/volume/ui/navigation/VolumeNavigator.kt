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

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.volume.VolumePanelFactory
import com.android.systemui.volume.domain.model.VolumePanelRoute
import com.android.systemui.volume.panel.ui.activity.VolumePanelActivity
import javax.inject.Inject

class VolumeNavigator
@Inject
constructor(
    @Application private val context: Context,
    private val volumePanelFactory: VolumePanelFactory,
    private val activityStarter: ActivityStarter,
) {

    fun openVolumePanel(route: VolumePanelRoute) {
        when (route) {
            VolumePanelRoute.COMPOSE_VOLUME_PANEL ->
                activityStarter.startActivityDismissingKeyguard(
                    /* intent = */ Intent(context, VolumePanelActivity::class.java),
                    /* onlyProvisioned = */ false,
                    /* dismissShade= */ true,
                    /* disallowEnterPictureInPictureWhileLaunching = */ true,
                    /* callback= */ null,
                    /* flags= */ 0,
                    /* animationController= */ null,
                    /* userHandle= */ null,
                )
            VolumePanelRoute.SETTINGS_VOLUME_PANEL ->
                activityStarter.startActivity(
                    /* intent= */ Intent(Settings.Panel.ACTION_VOLUME),
                    /* dismissShade= */ true
                )
            VolumePanelRoute.SYSTEM_UI_VOLUME_PANEL ->
                volumePanelFactory.create(aboveStatusBar = true, view = null)
        }
    }
}

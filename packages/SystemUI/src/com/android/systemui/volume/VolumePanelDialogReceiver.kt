/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.volume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.android.systemui.volume.domain.interactor.VolumePanelNavigationInteractor
import com.android.systemui.volume.ui.navigation.VolumeNavigator
import javax.inject.Inject

/** [BroadcastReceiver] for handling volume panel dialog intent */
class VolumePanelDialogReceiver
@Inject
constructor(
    private val volumeNavigator: VolumeNavigator,
    private val volumePanelNavigationInteractor: VolumePanelNavigationInteractor,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (
            TextUtils.equals(LAUNCH_ACTION, intent.action) ||
                TextUtils.equals(Settings.Panel.ACTION_VOLUME, intent.action)
        ) {
            volumeNavigator.openVolumePanel(volumePanelNavigationInteractor.getVolumePanelRoute())
        }
    }

    companion object {
        const val LAUNCH_ACTION = "com.android.systemui.action.LAUNCH_VOLUME_PANEL_DIALOG"
        const val DISMISS_ACTION = "com.android.systemui.action.DISMISS_VOLUME_PANEL_DIALOG"
    }
}

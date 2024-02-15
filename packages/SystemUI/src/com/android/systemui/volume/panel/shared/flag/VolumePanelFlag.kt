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

package com.android.systemui.volume.panel.shared.flag

import com.android.systemui.Flags
import com.android.systemui.compose.ComposeFacade
import com.android.systemui.flags.RefactorFlagUtils
import javax.inject.Inject

/** Provides a flag to check for the new Compose based Volume Panel availability. */
class VolumePanelFlag @Inject constructor() {

    /**
     * Returns true when the new Volume Panel is available and false the otherwise. The new panel
     * can only be available when [ComposeFacade.isComposeAvailable] is true.
     */
    fun canUseNewVolumePanel(): Boolean {
        return ComposeFacade.isComposeAvailable() && Flags.newVolumePanel()
    }

    fun assertNewVolumePanel() {
        require(ComposeFacade.isComposeAvailable())
        RefactorFlagUtils.assertInNewMode(Flags.newVolumePanel(), Flags.FLAG_NEW_VOLUME_PANEL)
    }
}

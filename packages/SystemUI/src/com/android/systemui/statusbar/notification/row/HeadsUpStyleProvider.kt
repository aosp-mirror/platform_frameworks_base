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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row

import android.app.Flags
import android.os.SystemProperties
import com.android.systemui.statusbar.data.repository.StatusBarModeRepositoryStore
import com.android.systemui.util.Compile
import javax.inject.Inject

/**
 * A class managing the heads up style to be applied based on user settings, immersive mode and
 * other factors.
 */
interface HeadsUpStyleProvider {
    fun shouldApplyCompactStyle(): Boolean
}

class HeadsUpStyleProviderImpl
@Inject
constructor(private val statusBarModeRepositoryStore: StatusBarModeRepositoryStore) :
    HeadsUpStyleProvider {

    override fun shouldApplyCompactStyle(): Boolean {
        return Flags.compactHeadsUpNotification() && (isInImmersiveMode() || alwaysShow())
    }

    private fun isInImmersiveMode() =
        statusBarModeRepositoryStore.defaultDisplay.isInFullscreenMode.value

    /** developer setting to always show Minimal HUN, even if the device is not in full screen */
    private fun alwaysShow() =
        Compile.IS_DEBUG &&
            SystemProperties.getBoolean("persist.compact_heads_up_notification.always_show", false)
}

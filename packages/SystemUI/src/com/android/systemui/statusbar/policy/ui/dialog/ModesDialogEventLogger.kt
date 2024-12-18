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

package com.android.systemui.statusbar.policy.ui.dialog

import com.android.internal.logging.UiEventLogger
import com.android.settingslib.notification.modes.ZenMode
import com.android.systemui.qs.QSModesEvent
import javax.inject.Inject

class ModesDialogEventLogger
@Inject
constructor(
    private val uiEventLogger: UiEventLogger,
) {

    fun logModeOn(mode: ZenMode) {
        val id =
            if (mode.isManualDnd) QSModesEvent.QS_MODES_DND_ON else QSModesEvent.QS_MODES_MODE_ON
        uiEventLogger.log(id, /* uid= */ 0, mode.rule.packageName)
    }

    fun logModeOff(mode: ZenMode) {
        val id =
            if (mode.isManualDnd) QSModesEvent.QS_MODES_DND_OFF else QSModesEvent.QS_MODES_MODE_OFF
        uiEventLogger.log(id, /* uid= */ 0, mode.rule.packageName)
    }

    fun logModeSettings(mode: ZenMode) {
        val id =
            if (mode.isManualDnd) QSModesEvent.QS_MODES_DND_SETTINGS
            else QSModesEvent.QS_MODES_MODE_SETTINGS
        uiEventLogger.log(id, /* uid= */ 0, mode.rule.packageName)
    }

    fun logOpenDurationDialog(mode: ZenMode) {
        // should only occur for manual Do Not Disturb.
        if (!mode.isManualDnd) {
            return
        }
        uiEventLogger.log(QSModesEvent.QS_MODES_DURATION_DIALOG)
    }

    fun logDialogSettings() {
        uiEventLogger.log(QSModesEvent.QS_MODES_SETTINGS)
    }
}

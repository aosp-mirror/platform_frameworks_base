/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.shared.quickaffordance

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.system.SysUiStatsLog

interface KeyguardQuickAffordancesMetricsLogger {

    /**
     * Logs shortcut Triggered
     * @param slotId The id of the lockscreen slot that the affordance is in
     * @param affordanceId The id of the lockscreen affordance
     */
    fun logOnShortcutTriggered(slotId: String, affordanceId: String)

    /**
     * Logs shortcut Selected
     * @param slotId The id of the lockscreen slot that the affordance is in
     * @param affordanceId The id of the lockscreen affordance
     */
    fun logOnShortcutSelected(slotId: String, affordanceId: String)

}

@SysUISingleton
class KeyguardQuickAffordancesMetricsLoggerImpl : KeyguardQuickAffordancesMetricsLogger {

    override fun logOnShortcutTriggered(slotId: String, affordanceId: String) {
        SysUiStatsLog.write(
                SysUiStatsLog.LOCKSCREEN_SHORTCUT_TRIGGERED,
                slotId,
                affordanceId,
        )
    }

    override fun logOnShortcutSelected(slotId: String, affordanceId: String) {
        SysUiStatsLog.write(
                SysUiStatsLog.LOCKSCREEN_SHORTCUT_SELECTED,
                slotId,
                affordanceId,
        )
    }
}
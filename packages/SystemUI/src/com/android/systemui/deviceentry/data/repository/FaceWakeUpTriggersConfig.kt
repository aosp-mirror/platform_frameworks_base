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

package com.android.systemui.deviceentry.data.repository

import android.content.res.Resources
import android.os.Build
import android.os.PowerManager
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.res.R
import com.android.systemui.util.settings.GlobalSettings
import dagger.Binds
import dagger.Module
import java.io.PrintWriter
import java.util.stream.Collectors
import javax.inject.Inject

/** Determines which device wake-ups should trigger passive authentication. */
interface FaceWakeUpTriggersConfig {
    fun shouldTriggerFaceAuthOnWakeUpFrom(@PowerManager.WakeReason pmWakeReason: Int): Boolean
    fun shouldTriggerFaceAuthOnWakeUpFrom(wakeReason: WakeSleepReason): Boolean
}

@SysUISingleton
class FaceWakeUpTriggersConfigImpl
@Inject
constructor(@Main resources: Resources, globalSettings: GlobalSettings, dumpManager: DumpManager) :
    Dumpable, FaceWakeUpTriggersConfig {
    private val defaultTriggerFaceAuthOnWakeUpFrom: Set<Int> =
        resources.getIntArray(R.array.config_face_auth_wake_up_triggers).toSet()
    private val triggerFaceAuthOnWakeUpFrom: Set<Int>
    private val wakeSleepReasonsToTriggerFaceAuth: Set<WakeSleepReason>

    init {
        triggerFaceAuthOnWakeUpFrom =
            if (Build.IS_DEBUGGABLE) {
                // Update face wake triggers via adb on debuggable builds:
                // ie: adb shell settings put global face_wake_triggers "1\|4" &&
                //     adb shell am crash com.android.systemui
                processStringArray(
                    globalSettings.getString("face_wake_triggers"),
                    defaultTriggerFaceAuthOnWakeUpFrom
                )
            } else {
                defaultTriggerFaceAuthOnWakeUpFrom
            }
        wakeSleepReasonsToTriggerFaceAuth =
            triggerFaceAuthOnWakeUpFrom
                .map {
                    val enumVal = WakeSleepReason.fromPowerManagerWakeReason(it)
                    assert(enumVal != WakeSleepReason.OTHER)
                    enumVal
                }
                .toSet()
        dumpManager.registerDumpable(this)
    }

    override fun shouldTriggerFaceAuthOnWakeUpFrom(
        @PowerManager.WakeReason pmWakeReason: Int
    ): Boolean {
        return triggerFaceAuthOnWakeUpFrom.contains(pmWakeReason)
    }

    override fun shouldTriggerFaceAuthOnWakeUpFrom(wakeReason: WakeSleepReason): Boolean =
        wakeSleepReasonsToTriggerFaceAuth.contains(wakeReason)

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("FaceWakeUpTriggers:")
        for (pmWakeReason in triggerFaceAuthOnWakeUpFrom) {
            pw.println("    ${PowerManager.wakeReasonToString(pmWakeReason)}")
        }
    }

    /** Convert a pipe-separated set of integers into a set of ints. */
    private fun processStringArray(stringSetting: String?, default: Set<Int>): Set<Int> {
        return stringSetting?.let {
            stringSetting.split("|").stream().map(Integer::parseInt).collect(Collectors.toSet())
        }
            ?: default
    }
}

@Module
interface FaceWakeUpTriggersConfigModule {
    @Binds fun repository(impl: FaceWakeUpTriggersConfigImpl): FaceWakeUpTriggersConfig
}

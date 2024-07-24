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

package com.android.systemui.communal.data.model

import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger
import java.util.EnumSet

/** Reasons that communal is disabled, primarily for logging. */
enum class DisabledReason(val loggingString: String) {
    /** Communal should be disabled due to invalid current user */
    DISABLED_REASON_INVALID_USER("invalidUser"),
    /** Communal should be disabled due to the flag being off */
    DISABLED_REASON_FLAG("flag"),
    /** Communal should be disabled because the user has turned off the setting */
    DISABLED_REASON_USER_SETTING("userSetting"),
    /** Communal is disabled by the device policy app */
    DISABLED_REASON_DEVICE_POLICY("devicePolicy"),
}

/**
 * Model representing the reasons communal hub should be disabled. Allows logging reasons separately
 * for debugging.
 */
@JvmInline
value class CommunalEnabledState(
    private val disabledReasons: EnumSet<DisabledReason> =
        EnumSet.noneOf(DisabledReason::class.java)
) : Diffable<CommunalEnabledState>, Set<DisabledReason> by disabledReasons {

    /** Creates [CommunalEnabledState] with a single reason for being disabled */
    constructor(reason: DisabledReason) : this(EnumSet.of(reason))

    /** Checks if there are any reasons communal should be disabled. If none, returns true. */
    val enabled: Boolean
        get() = isEmpty()

    override fun logDiffs(prevVal: CommunalEnabledState, row: TableRowLogger) {
        for (reason in DisabledReason.entries) {
            val newVal = contains(reason)
            if (newVal != prevVal.contains(reason)) {
                row.logChange(
                    columnName = reason.loggingString,
                    value = newVal,
                )
            }
        }
    }

    override fun logFull(row: TableRowLogger) {
        for (reason in DisabledReason.entries) {
            row.logChange(columnName = reason.loggingString, value = contains(reason))
        }
    }
}

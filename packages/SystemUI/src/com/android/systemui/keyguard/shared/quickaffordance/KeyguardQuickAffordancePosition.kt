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

package com.android.systemui.keyguard.shared.quickaffordance

import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START

/** Enumerates all possible positions for quick affordances that can appear on the lock-screen. */
enum class KeyguardQuickAffordancePosition {
    BOTTOM_START,
    BOTTOM_END;

    fun toSlotId(): String {
        return when (this) {
            BOTTOM_START -> SLOT_ID_BOTTOM_START
            BOTTOM_END -> SLOT_ID_BOTTOM_END
        }
    }

    companion object {

        /** If the slot ID does not match any string, return null. */
        fun parseKeyguardQuickAffordancePosition(slotId: String): KeyguardQuickAffordancePosition? =
            when (slotId) {
                SLOT_ID_BOTTOM_START -> BOTTOM_START
                SLOT_ID_BOTTOM_END -> BOTTOM_END
                else -> null
            }
    }
}

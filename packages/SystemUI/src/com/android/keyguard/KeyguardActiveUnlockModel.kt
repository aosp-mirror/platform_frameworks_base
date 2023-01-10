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

package com.android.keyguard

import android.annotation.CurrentTimeMillisLong
import com.android.systemui.dump.DumpsysTableLogger
import com.android.systemui.dump.Row
import com.android.systemui.plugins.util.RingBuffer

/** Verbose debug information. */
data class KeyguardActiveUnlockModel(
    @CurrentTimeMillisLong override var timeMillis: Long = 0L,
    override var userId: Int = 0,
    override var listening: Boolean = false,
    // keep sorted
    var awakeKeyguard: Boolean = false,
    var authInterruptActive: Boolean = false,
    var fpLockedOut: Boolean = false,
    var primaryAuthRequired: Boolean = false,
    var switchingUser: Boolean = false,
    var triggerActiveUnlockForAssistant: Boolean = false,
    var userCanDismissLockScreen: Boolean = false,
) : KeyguardListenModel() {

    /** List of [String] to be used as a [Row] with [DumpsysTableLogger]. */
    val asStringList: List<String> by lazy {
        listOf(
            DATE_FORMAT.format(timeMillis),
            timeMillis.toString(),
            userId.toString(),
            listening.toString(),
            // keep sorted
            awakeKeyguard.toString(),
            authInterruptActive.toString(),
            fpLockedOut.toString(),
            primaryAuthRequired.toString(),
            switchingUser.toString(),
            triggerActiveUnlockForAssistant.toString(),
            userCanDismissLockScreen.toString(),
        )
    }

    /**
     * [RingBuffer] to store [KeyguardActiveUnlockModel]. After the buffer is full, it will recycle
     * old events.
     *
     * Do not use [append] to add new elements. Instead use [insert], as it will recycle if
     * necessary.
     */
    class Buffer {
        private val buffer = RingBuffer(CAPACITY) { KeyguardActiveUnlockModel() }

        fun insert(model: KeyguardActiveUnlockModel) {
            buffer.advance().apply {
                timeMillis = model.timeMillis
                userId = model.userId
                listening = model.listening
                // keep sorted
                awakeKeyguard = model.awakeKeyguard
                authInterruptActive = model.authInterruptActive
                fpLockedOut = model.fpLockedOut
                primaryAuthRequired = model.primaryAuthRequired
                switchingUser = model.switchingUser
                triggerActiveUnlockForAssistant = model.triggerActiveUnlockForAssistant
                userCanDismissLockScreen = model.userCanDismissLockScreen
            }
        }

        /**
         * Returns the content of the buffer (sorted from latest to newest).
         *
         * @see KeyguardFingerprintListenModel.asStringList
         */
        fun toList(): List<Row> {
            return buffer.asSequence().map { it.asStringList }.toList()
        }
    }

    companion object {
        const val CAPACITY = 20 // number of logs to retain

        /** Headers for dumping a table using [DumpsysTableLogger]. */
        @JvmField
        val TABLE_HEADERS =
            listOf(
                "timestamp",
                "time_millis",
                "userId",
                "listening",
                // keep sorted
                "awakeKeyguard",
                "authInterruptActive",
                "fpLockedOut",
                "primaryAuthRequired",
                "switchingUser",
                "triggerActiveUnlockForAssistant",
                "userCanDismissLockScreen",
            )
    }
}

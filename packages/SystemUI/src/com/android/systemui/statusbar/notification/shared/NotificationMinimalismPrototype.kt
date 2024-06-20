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

package com.android.systemui.statusbar.notification.shared

import android.os.SystemProperties
import com.android.systemui.Flags
import com.android.systemui.flags.FlagToken
import com.android.systemui.flags.RefactorFlagUtils

/** Helper for reading or using the minimalism prototype flag state. */
@Suppress("NOTHING_TO_INLINE")
object NotificationMinimalismPrototype {

    val version: Int by lazy {
        SystemProperties.getInt("persist.notification_minimalism_prototype.version", 2)
    }

    object V1 {
        /** The aconfig flag name */
        const val FLAG_NAME = Flags.FLAG_NOTIFICATION_MINIMALISM_PROTOTYPE

        /** A token used for dependency declaration */
        val token: FlagToken
            get() = FlagToken(FLAG_NAME, isEnabled)

        /** Is the heads-up cycling animation enabled */
        @JvmStatic
        inline val isEnabled
            get() = Flags.notificationMinimalismPrototype() && version == 1

        /**
         * the prototype will now show seen notifications on the locked shade by default, but this
         * property read allows that to be quickly disabled for testing
         */
        val showOnLockedShade: Boolean
            get() =
                if (isUnexpectedlyInLegacyMode()) false
                else
                    SystemProperties.getBoolean(
                        "persist.notification_minimalism_prototype.show_on_locked_shade",
                        true
                    )

        /** gets the configurable max number of notifications */
        val maxNotifs: Int
            get() =
                if (isUnexpectedlyInLegacyMode()) -1
                else
                    SystemProperties.getInt(
                        "persist.notification_minimalism_prototype.lock_screen_max_notifs",
                        1
                    )

        /**
         * Called to ensure code is only run when the flag is enabled. This protects users from the
         * unintended behaviors caused by accidentally running new logic, while also crashing on an
         * eng build to ensure that the refactor author catches issues in testing.
         */
        @JvmStatic
        inline fun isUnexpectedlyInLegacyMode() =
            RefactorFlagUtils.isUnexpectedlyInLegacyMode(isEnabled, FLAG_NAME)

        /**
         * Called to ensure code is only run when the flag is disabled. This will throw an exception
         * if the flag is enabled to ensure that the refactor author catches issues in testing.
         */
        @JvmStatic
        inline fun assertInLegacyMode() = RefactorFlagUtils.assertInLegacyMode(isEnabled, FLAG_NAME)
    }
    object V2 {
        const val FLAG_NAME = Flags.FLAG_NOTIFICATION_MINIMALISM_PROTOTYPE

        /** A token used for dependency declaration */
        val token: FlagToken
            get() = FlagToken(FLAG_NAME, isEnabled)

        /** Is the heads-up cycling animation enabled */
        @JvmStatic
        inline val isEnabled
            get() = Flags.notificationMinimalismPrototype() && version == 2

        /**
         * The prototype will (by default) use a promoter to ensure that the top unseen notification
         * is not grouped, but this property read allows that behavior to be disabled.
         */
        val ungroupTopUnseen: Boolean
            get() =
                if (isUnexpectedlyInLegacyMode()) false
                else
                    SystemProperties.getBoolean(
                        "persist.notification_minimalism_prototype.ungroup_top_unseen",
                        true
                    )

        /**
         * Called to ensure code is only run when the flag is enabled. This protects users from the
         * unintended behaviors caused by accidentally running new logic, while also crashing on an
         * eng build to ensure that the refactor author catches issues in testing.
         */
        @JvmStatic
        inline fun isUnexpectedlyInLegacyMode() =
            RefactorFlagUtils.isUnexpectedlyInLegacyMode(isEnabled, FLAG_NAME)

        /**
         * Called to ensure code is only run when the flag is disabled. This will throw an exception
         * if the flag is enabled to ensure that the refactor author catches issues in testing.
         */
        @JvmStatic
        inline fun assertInLegacyMode() = RefactorFlagUtils.assertInLegacyMode(isEnabled, FLAG_NAME)
    }
}

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

package com.android.systemui.modes.shared

import android.app.Flags
import com.android.systemui.flags.RefactorFlagUtils

/** Helper for reading or using the modes ui icons flag state. */
@Suppress("NOTHING_TO_INLINE")
object ModesUiIcons {
    /** Is the refactor enabled */
    @JvmStatic
    inline val isEnabled
        get() = ModesUi.isEnabled && Flags.modesUiIcons()

    /**
     * Called to ensure code is only run when the flag is enabled. This protects users from the
     * unintended behaviors caused by accidentally running new logic, while also crashing on an eng
     * build to ensure that the refactor author catches issues in testing.
     */
    @JvmStatic
    inline fun isUnexpectedlyInLegacyMode() =
        RefactorFlagUtils.isUnexpectedlyInLegacyMode(isEnabled, Flags.FLAG_MODES_UI_ICONS)

    /**
     * Called to ensure code is only run when the flag is disabled. This will throw an exception if
     * the flag is not enabled to ensure that the refactor author catches issues in testing.
     * Caution!! Using this check incorrectly will cause crashes in nextfood builds!
     */
    @JvmStatic
    inline fun assertInNewMode() =
        RefactorFlagUtils.assertInNewMode(isEnabled, Flags.FLAG_MODES_UI_ICONS)

    /**
     * Called to ensure code is only run when the flag is disabled. This will throw an exception if
     * the flag is enabled to ensure that the refactor author catches issues in testing.
     */
    @JvmStatic
    inline fun assertInLegacyMode() =
        RefactorFlagUtils.assertInLegacyMode(isEnabled, Flags.FLAG_MODES_UI_ICONS)
}

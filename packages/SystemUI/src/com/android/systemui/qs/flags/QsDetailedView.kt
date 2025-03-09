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

package com.android.systemui.qs.flags

import com.android.systemui.Flags
import com.android.systemui.flags.FlagToken
import com.android.systemui.flags.RefactorFlagUtils
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.shared.flag.DualShade

/** Helper for reading or using the QS Detailed View flag state. */
@Suppress("NOTHING_TO_INLINE")
object QsDetailedView {
    /** The aconfig flag name */
    const val FLAG_NAME = Flags.FLAG_QS_TILE_DETAILED_VIEW

    /** A token used for dependency declaration */
    val token: FlagToken
        get() = FlagToken(FLAG_NAME, isEnabled)

    /** Is the flag enabled */
    @JvmStatic
    inline val isEnabled
        get() =
            Flags.qsTileDetailedView() && // mainAconfigFlag
                DualShade.isEnabled &&
                SceneContainerFlag.isEnabled

    // NOTE: Changes should also be made in getSecondaryFlags

    /** The main aconfig flag. */
    inline fun getMainAconfigFlag() = FlagToken(FLAG_NAME, Flags.qsTileDetailedView())

    /** The set of secondary flags which must be enabled for qs detailed view to work properly */
    inline fun getSecondaryFlags(): Sequence<FlagToken> =
        sequenceOf(
            DualShade.token
            // NOTE: Changes should also be made in isEnabled
        ) + SceneContainerFlag.getAllRequirements()

    /** The full set of requirements for QsDetailedView */
    inline fun getAllRequirements(): Sequence<FlagToken> {
        return sequenceOf(getMainAconfigFlag()) + getSecondaryFlags()
    }

    /** Return all dependencies of this flag in pairs where [Pair.first] depends on [Pair.second] */
    inline fun getFlagDependencies(): Sequence<Pair<FlagToken, FlagToken>> {
        val mainAconfigFlag = getMainAconfigFlag()
        return getSecondaryFlags().map { mainAconfigFlag to it }
    }

    /**
     * Called to ensure code is only run when the flag is enabled. This protects users from the
     * unintended behaviors caused by accidentally running new logic, while also crashing on an eng
     * build to ensure that the refactor author catches issues in testing.
     */
    @JvmStatic
    inline fun isUnexpectedlyInLegacyMode() =
        RefactorFlagUtils.isUnexpectedlyInLegacyMode(isEnabled, FLAG_NAME)

    /**
     * Called to ensure code is only run when the flag is disabled. This will throw an exception if
     * the flag is enabled to ensure that the refactor author catches issues in testing.
     */
    @JvmStatic
    inline fun assertInLegacyMode() = RefactorFlagUtils.assertInLegacyMode(isEnabled, FLAG_NAME)

    /** Returns a developer-readable string that describes the current requirement list. */
    @JvmStatic
    fun requirementDescription(): String {
        return buildString {
            getAllRequirements().forEach { requirement ->
                append('\n')
                append(if (requirement.isEnabled) "    [MET]" else "[NOT MET]")
                append(" ${requirement.name}")
            }
        }
    }
}

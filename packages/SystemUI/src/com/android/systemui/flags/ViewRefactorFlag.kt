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

package com.android.systemui.flags

import android.util.Log
import com.android.systemui.Dependency

/**
 * This class promotes best practices for flag guarding System UI view refactors.
 * * [isEnabled] allows changing an implementation.
 * * [assertDisabled] allows authors to flag code as being "dead" when the flag gets enabled and
 *   ensure that it is not being invoked accidentally in the post-flag refactor.
 * * [expectEnabled] allows authors to guard new code with a "safe" alternative when invoked on
 *   flag-disabled builds, but with a check that should crash eng builds or tests when the
 *   expectation is violated.
 *
 * The constructors prefer that you provide a [FeatureFlags] instance, but does not require it,
 * falling back to [Dependency.get]. This fallback should ONLY be used to flag-guard code changes
 * inside views where injecting flag values after initialization can be error-prone.
 */
class ViewRefactorFlag
private constructor(
    private val injectedFlags: FeatureFlags?,
    private val flag: BooleanFlag,
    private val readFlagValue: (FeatureFlags) -> Boolean
) {
    @JvmOverloads
    constructor(
        flags: FeatureFlags? = null,
        flag: UnreleasedFlag
    ) : this(flags, flag, { it.isEnabled(flag) })

    @JvmOverloads
    constructor(
        flags: FeatureFlags? = null,
        flag: ReleasedFlag
    ) : this(flags, flag, { it.isEnabled(flag) })

    /** Whether the flag is enabled. Called to switch between an old behavior and a new behavior. */
    val isEnabled by lazy {
        @Suppress("DEPRECATION")
        val featureFlags = injectedFlags ?: Dependency.get(FeatureFlags::class.java)
        readFlagValue(featureFlags)
    }

    /**
     * Called to ensure code is only run when the flag is disabled. This will throw an exception if
     * the flag is enabled to ensure that the refactor author catches issues in testing.
     *
     * Example usage:
     * ```
     * public void setController(NotificationShelfController notificationShelfController) {
     *     mShelfRefactor.assertDisabled();
     *     mController = notificationShelfController;
     * }
     * ````
     */
    fun assertDisabled() = check(!isEnabled) { "Code path not supported when $flag is enabled." }

    /**
     * Called to ensure code is only run when the flag is enabled. This protects users from the
     * unintended behaviors caused by accidentally running new logic, while also crashing on an eng
     * build to ensure that the refactor author catches issues in testing.
     *
     * Example usage:
     * ```
     * public void setShelfIcons(NotificationIconContainer icons) {
     *     if (mShelfRefactor.expectEnabled()) {
     *         mShelfIcons = icons;
     *     }
     * }
     * ```
     */
    fun expectEnabled(): Boolean {
        if (!isEnabled) {
            val message = "Code path not supported when $flag is disabled."
            Log.wtf(TAG, message, Exception(message))
        }
        return isEnabled
    }

    private companion object {
        private const val TAG = "ViewRefactorFlag"
    }
}

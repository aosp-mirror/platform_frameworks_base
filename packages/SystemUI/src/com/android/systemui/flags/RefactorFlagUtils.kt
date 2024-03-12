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

import android.os.Build
import android.util.Log

/**
 * Utilities for writing your own objects to uphold refactor flag conventions.
 *
 * Example usage:
 * ```
 * object SomeRefactor {
 *     const val FLAG_NAME = Flags.SOME_REFACTOR
 *     val token: FlagToken get() = FlagToken(FLAG_NAME, isEnabled)
 *     @JvmStatic inline val isEnabled get() = Flags.someRefactor()
 *     @JvmStatic inline fun isUnexpectedlyInLegacyMode() =
 *         RefactorFlagUtils.isUnexpectedlyInLegacyMode(isEnabled, FLAG_NAME)
 *     @JvmStatic inline fun assertInLegacyMode() =
 *         RefactorFlagUtils.assertInLegacyMode(isEnabled, FLAG_NAME)
 * }
 * ```
 *
 * Legacy mode crashes can be disabled with the command:
 * ```
 * adb shell setprop log.tag.RefactorFlagAssert silent
 * ```
 */
@Suppress("NOTHING_TO_INLINE")
object RefactorFlagUtils {
    /**
     * Called to ensure code is only run when the flag is enabled. This protects users from the
     * unintended behaviors caused by accidentally running new logic, while also crashing on an eng
     * build to ensure that the refactor author catches issues in testing.
     *
     * Example usage:
     * ```
     * public void setNewController(SomeController someController) {
     *     if (SomeRefactor.isUnexpectedlyInLegacyMode()) return;
     *     mSomeController = someController;
     * }
     * ```
     */
    inline fun isUnexpectedlyInLegacyMode(isEnabled: Boolean, flagName: Any): Boolean {
        val inLegacyMode = !isEnabled
        if (inLegacyMode) {
            assertOnEngBuild("New code path expects $flagName to be enabled.")
        }
        return inLegacyMode
    }

    /**
     * Called to ensure code is only run when the flag is disabled. This will throw an exception if
     * the flag is enabled to ensure that the refactor author catches issues in testing.
     *
     * Example usage:
     * ```
     * public void setSomeLegacyController(SomeController someController) {
     *     SomeRefactor.assertInLegacyMode();
     *     mSomeController = someController;
     * }
     * ````
     */
    inline fun assertInLegacyMode(isEnabled: Boolean, flagName: Any) =
        check(!isEnabled) { "Legacy code path not supported when $flagName is enabled." }

    /**
     * Called to ensure the new code is only run when the flag is enabled. This will throw an
     * exception if the flag is disabled to ensure that the refactor author catches issues in
     * testing.
     *
     * Example usage:
     * ```
     * public void setSomeNewController(SomeController someController) {
     *     SomeRefactor.assertInNewMode();
     *     mSomeController = someController;
     * }
     * ````
     */
    inline fun assertInNewMode(isEnabled: Boolean, flagName: Any) =
        check(isEnabled) { "New code path not supported when $flagName is disabled." }

    /**
     * This will [Log.wtf] with the given message, assuming [ASSERT_TAG] is loggable at that level.
     * This means an engineer can prevent this from crashing by running the command:
     * ```
     * adb shell setprop log.tag.RefactorFlagAssert silent
     * ```
     */
    fun assertOnEngBuild(message: String) {
        if (Log.isLoggable(ASSERT_TAG, Log.ASSERT)) {
            val exception = if (Build.isDebuggable()) IllegalStateException(message) else null
            Log.wtf(ASSERT_TAG, message, exception)
        } else if (Log.isLoggable(STANDARD_TAG, Log.WARN)) {
            Log.w(STANDARD_TAG, message)
        }
    }

    /**
     * Tag used to determine if an incorrect flag guard should crash System UI running an eng build.
     * This is enabled by default. To disable, run:
     * ```
     * adb shell setprop log.tag.RefactorFlagAssert silent
     * ```
     */
    private const val ASSERT_TAG = "RefactorFlagAssert"

    /** Tag used for non-crashing logs or when the [ASSERT_TAG] has been silenced. */
    private const val STANDARD_TAG = "RefactorFlag"
}

/** An object which allows dependency tracking */
data class FlagToken(val name: String, val isEnabled: Boolean) {
    override fun toString(): String = "$name (${if (isEnabled) "enabled" else "disabled"})"
}

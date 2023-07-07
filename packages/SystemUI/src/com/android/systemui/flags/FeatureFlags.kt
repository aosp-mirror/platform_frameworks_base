/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.util.Dumpable

/**
 * Class to manage simple DeviceConfig-based feature flags.
 *
 * See [Flags] for instructions on defining new flags.
 */
interface FeatureFlags : FlagListenable, Dumpable {
    /** Returns a boolean value for the given flag.  */
    fun isEnabled(flag: UnreleasedFlag): Boolean

    /** Returns a boolean value for the given flag.  */
    fun isEnabled(flag: ReleasedFlag): Boolean

    /** Returns a boolean value for the given flag.  */
    fun isEnabled(flag: ResourceBooleanFlag): Boolean

    /** Returns a boolean value for the given flag.  */
    fun isEnabled(flag: SysPropBooleanFlag): Boolean

    /** Returns a string value for the given flag.  */
    fun getString(flag: StringFlag): String

    /** Returns a string value for the given flag.  */
    fun getString(flag: ResourceStringFlag): String

    /** Returns an int value for a given flag/ */
    fun getInt(flag: IntFlag): Int

    /** Returns an int value for a given flag/ */
    fun getInt(flag: ResourceIntFlag): Int
}

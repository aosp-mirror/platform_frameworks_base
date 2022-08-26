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

package com.android.systemui.flags

import android.util.SparseArray
import android.util.SparseBooleanArray
import androidx.core.util.containsKey

class FakeFeatureFlags : FeatureFlags {
    private val booleanFlags = SparseBooleanArray()
    private val stringFlags = SparseArray<String>()
    private val knownFlagNames = mutableMapOf<Int, String>()

    init {
        Flags.getFlagFields().forEach { field ->
            val flag: Flag<*> = field.get(null) as Flag<*>
            knownFlagNames[flag.id] = field.name
        }
    }

    fun set(flag: BooleanFlag, value: Boolean) {
        booleanFlags.put(flag.id, value)
    }

    fun set(flag: DeviceConfigBooleanFlag, value: Boolean) {
        booleanFlags.put(flag.id, value)
    }

    fun set(flag: ResourceBooleanFlag, value: Boolean) {
        booleanFlags.put(flag.id, value)
    }

    fun set(flag: SysPropBooleanFlag, value: Boolean) {
        booleanFlags.put(flag.id, value)
    }

    fun set(flag: StringFlag, value: String) {
        stringFlags.put(flag.id, value)
    }

    fun set(flag: ResourceStringFlag, value: String) {
        stringFlags.put(flag.id, value)
    }

    override fun isEnabled(flag: UnreleasedFlag): Boolean = requireBooleanValue(flag.id)

    override fun isEnabled(flag: ReleasedFlag): Boolean = requireBooleanValue(flag.id)

    override fun isEnabled(flag: ResourceBooleanFlag): Boolean = requireBooleanValue(flag.id)

    override fun isEnabled(flag: DeviceConfigBooleanFlag): Boolean = requireBooleanValue(flag.id)

    override fun isEnabled(flag: SysPropBooleanFlag): Boolean = requireBooleanValue(flag.id)

    override fun getString(flag: StringFlag): String = requireStringValue(flag.id)

    override fun getString(flag: ResourceStringFlag): String = requireStringValue(flag.id)

    override fun addListener(flag: Flag<*>, listener: FlagListenable.Listener) {}

    override fun removeListener(listener: FlagListenable.Listener) {}

    private fun flagName(flagId: Int): String {
        return knownFlagNames[flagId] ?: "UNKNOWN(id=$flagId)"
    }

    private fun requireBooleanValue(flagId: Int): Boolean {
        if (!booleanFlags.containsKey(flagId)) {
            throw IllegalStateException("Flag ${flagName(flagId)} was accessed but not specified.")
        }
        return booleanFlags[flagId]
    }

    private fun requireStringValue(flagId: Int): String {
        if (!stringFlags.containsKey(flagId)) {
            throw IllegalStateException("Flag ${flagName(flagId)} was accessed but not specified.")
        }
        return stringFlags[flagId]
    }
}

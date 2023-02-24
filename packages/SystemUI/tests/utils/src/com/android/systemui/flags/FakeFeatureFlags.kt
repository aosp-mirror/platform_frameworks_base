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

class FakeFeatureFlags : FeatureFlags {
    private val booleanFlags = mutableMapOf<Int, Boolean>()
    private val stringFlags = mutableMapOf<Int, String>()
    private val knownFlagNames = mutableMapOf<Int, String>()
    private val flagListeners = mutableMapOf<Int, MutableSet<FlagListenable.Listener>>()
    private val listenerFlagIds = mutableMapOf<FlagListenable.Listener, MutableSet<Int>>()

    init {
        Flags.getFlagFields().forEach { field ->
            val flag: Flag<*> = field.get(null) as Flag<*>
            knownFlagNames[flag.id] = field.name
        }
    }

    fun set(flag: BooleanFlag, value: Boolean) {
        if (booleanFlags.put(flag.id, value)?.let { value != it } != false) {
            notifyFlagChanged(flag)
        }
    }

    fun set(flag: DeviceConfigBooleanFlag, value: Boolean) {
        if (booleanFlags.put(flag.id, value)?.let { value != it } != false) {
            notifyFlagChanged(flag)
        }
    }

    fun set(flag: ResourceBooleanFlag, value: Boolean) {
        if (booleanFlags.put(flag.id, value)?.let { value != it } != false) {
            notifyFlagChanged(flag)
        }
    }

    fun set(flag: SysPropBooleanFlag, value: Boolean) {
        if (booleanFlags.put(flag.id, value)?.let { value != it } != false) {
            notifyFlagChanged(flag)
        }
    }

    fun set(flag: StringFlag, value: String) {
        if (stringFlags.put(flag.id, value)?.let { value != it } == null) {
            notifyFlagChanged(flag)
        }
    }

    fun set(flag: ResourceStringFlag, value: String) {
        if (stringFlags.put(flag.id, value)?.let { value != it } == null) {
            notifyFlagChanged(flag)
        }
    }

    private fun notifyFlagChanged(flag: Flag<*>) {
        flagListeners[flag.id]?.let { listeners ->
            listeners.forEach { listener ->
                listener.onFlagChanged(
                    object : FlagListenable.FlagEvent {
                        override val flagId = flag.id
                        override fun requestNoRestart() {}
                    }
                )
            }
        }
    }

    override fun isEnabled(flag: UnreleasedFlag): Boolean = requireBooleanValue(flag.id)

    override fun isEnabled(flag: ReleasedFlag): Boolean = requireBooleanValue(flag.id)

    override fun isEnabled(flag: ResourceBooleanFlag): Boolean = requireBooleanValue(flag.id)

    override fun isEnabled(flag: DeviceConfigBooleanFlag): Boolean = requireBooleanValue(flag.id)

    override fun isEnabled(flag: SysPropBooleanFlag): Boolean = requireBooleanValue(flag.id)

    override fun getString(flag: StringFlag): String = requireStringValue(flag.id)

    override fun getString(flag: ResourceStringFlag): String = requireStringValue(flag.id)

    override fun addListener(flag: Flag<*>, listener: FlagListenable.Listener) {
        flagListeners.getOrPut(flag.id) { mutableSetOf() }.add(listener)
        listenerFlagIds.getOrPut(listener) { mutableSetOf() }.add(flag.id)
    }

    override fun removeListener(listener: FlagListenable.Listener) {
        listenerFlagIds.remove(listener)?.let {
                flagIds -> flagIds.forEach {
                        id -> flagListeners[id]?.remove(listener)
                }
        }
    }

    private fun flagName(flagId: Int): String {
        return knownFlagNames[flagId] ?: "UNKNOWN(id=$flagId)"
    }

    private fun requireBooleanValue(flagId: Int): Boolean {
        return booleanFlags[flagId]
            ?: error("Flag ${flagName(flagId)} was accessed but not specified.")
    }

    private fun requireStringValue(flagId: Int): String {
        return stringFlags[flagId]
            ?: error("Flag ${flagName(flagId)} was accessed but not specified.")
    }
}

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

import java.io.PrintWriter

class FakeFeatureFlags : FeatureFlags {
    private val booleanFlags = mutableMapOf<Int, Boolean>()
    private val stringFlags = mutableMapOf<Int, String>()
    private val intFlags = mutableMapOf<Int, Int>()
    private val knownFlagNames = mutableMapOf<Int, String>()
    private val flagListeners = mutableMapOf<Int, MutableSet<FlagListenable.Listener>>()
    private val listenerFlagIds = mutableMapOf<FlagListenable.Listener, MutableSet<Int>>()

    init {
        FlagsFactory.knownFlags.forEach { entry: Map.Entry<String, Flag<*>> ->
            knownFlagNames[entry.value.id] = entry.key
        }
    }

    fun set(flag: BooleanFlag, value: Boolean) {
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

    /**
     * Set the given flag's default value if no other value has been set.
     *
     * REMINDER: You should always test your code with your flag in both configurations, so
     *  generally you should be setting a particular value.  This method should be reserved for
     *  situations where the flag needs to be read (e.g. in the class constructor), but its
     *  value shouldn't affect the actual test cases. In those cases, it's mildly safer to use
     *  this method than to hard-code `false` or `true` because then at least if you're wrong,
     *  and the flag value *does* matter, you'll notice when the flag is flipped and tests
     *  start failing.
     */
    fun setDefault(flag: BooleanFlag) = booleanFlags.putIfAbsent(flag.id, flag.default)

    /**
     * Set the given flag's default value if no other value has been set.
     *
     * REMINDER: You should always test your code with your flag in both configurations, so
     *  generally you should be setting a particular value.  This method should be reserved for
     *  situations where the flag needs to be read (e.g. in the class constructor), but its
     *  value shouldn't affect the actual test cases. In those cases, it's mildly safer to use
     *  this method than to hard-code `false` or `true` because then at least if you're wrong,
     *  and the flag value *does* matter, you'll notice when the flag is flipped and tests
     *  start failing.
     */
    fun setDefault(flag: SysPropBooleanFlag) = booleanFlags.putIfAbsent(flag.id, flag.default)

    private fun notifyFlagChanged(flag: Flag<*>) {
        flagListeners[flag.id]?.let { listeners ->
            listeners.forEach { listener ->
                listener.onFlagChanged(
                    object : FlagListenable.FlagEvent {
                        override val flagName = flag.name
                        override fun requestNoRestart() {}
                    }
                )
            }
        }
    }

    override fun isEnabled(flag: UnreleasedFlag): Boolean = requireBooleanValue(flag.id)

    override fun isEnabled(flag: ReleasedFlag): Boolean = requireBooleanValue(flag.id)

    override fun isEnabled(flag: ResourceBooleanFlag): Boolean = requireBooleanValue(flag.id)

    override fun isEnabled(flag: SysPropBooleanFlag): Boolean = requireBooleanValue(flag.id)

    override fun getString(flag: StringFlag): String = requireStringValue(flag.id)

    override fun getString(flag: ResourceStringFlag): String = requireStringValue(flag.id)

    override fun getInt(flag: IntFlag): Int = requireIntValue(flag.id)

    override fun getInt(flag: ResourceIntFlag): Int = requireIntValue(flag.id)

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

    override fun dump(writer: PrintWriter, args: Array<out String>?) {
        // no-op
    }

    private fun flagName(flagId: Int): String {
        return knownFlagNames[flagId] ?: "UNKNOWN(id=$flagId)"
    }

    private fun requireBooleanValue(flagId: Int): Boolean {
        return booleanFlags[flagId]
            ?: error("Flag ${flagName(flagId)} was accessed as boolean but not specified.")
    }

    private fun requireStringValue(flagId: Int): String {
        return stringFlags[flagId]
            ?: error("Flag ${flagName(flagId)} was accessed as string but not specified.")
    }

    private fun requireIntValue(flagId: Int): Int {
        return intFlags[flagId]
            ?: error("Flag ${flagName(flagId)} was accessed as int but not specified.")
    }
}

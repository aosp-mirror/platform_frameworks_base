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

import dagger.Binds
import dagger.Module
import dagger.Provides
import java.io.PrintWriter

class FakeFeatureFlagsClassic : FakeFeatureFlags()

@Deprecated(
    message = "Use FakeFeatureFlagsClassic instead.",
    replaceWith =
        ReplaceWith(
            "FakeFeatureFlagsClassic",
            "com.android.systemui.flags.FakeFeatureFlagsClassic",
        ),
)
open class FakeFeatureFlags : FeatureFlagsClassic {
    private val booleanFlags = mutableMapOf<String, Boolean>()
    private val stringFlags = mutableMapOf<String, String>()
    private val intFlags = mutableMapOf<String, Int>()
    private val knownFlagNames = mutableMapOf<String, String>()
    private val flagListeners = mutableMapOf<String, MutableSet<FlagListenable.Listener>>()
    private val listenerflagNames = mutableMapOf<FlagListenable.Listener, MutableSet<String>>()

    init {
        FlagsFactory.knownFlags.forEach { entry: Map.Entry<String, Flag<*>> ->
            knownFlagNames[entry.value.name] = entry.key
        }
    }

    fun set(flag: BooleanFlag, value: Boolean) {
        if (booleanFlags.put(flag.name, value)?.let { value != it } != false) {
            notifyFlagChanged(flag)
        }
    }

    fun set(flag: ResourceBooleanFlag, value: Boolean) {
        if (booleanFlags.put(flag.name, value)?.let { value != it } != false) {
            notifyFlagChanged(flag)
        }
    }

    fun set(flag: SysPropBooleanFlag, value: Boolean) {
        if (booleanFlags.put(flag.name, value)?.let { value != it } != false) {
            notifyFlagChanged(flag)
        }
    }

    fun set(flag: StringFlag, value: String) {
        if (stringFlags.put(flag.name, value)?.let { value != it } == null) {
            notifyFlagChanged(flag)
        }
    }

    fun set(flag: ResourceStringFlag, value: String) {
        if (stringFlags.put(flag.name, value)?.let { value != it } == null) {
            notifyFlagChanged(flag)
        }
    }

    /**
     * Set the given flag's default value if no other value has been set.
     *
     * REMINDER: You should always test your code with your flag in both configurations, so
     * generally you should be setting a particular value. This method should be reserved for
     * situations where the flag needs to be read (e.g. in the class constructor), but its value
     * shouldn't affect the actual test cases. In those cases, it's mildly safer to use this method
     * than to hard-code `false` or `true` because then at least if you're wrong, and the flag value
     * *does* matter, you'll notice when the flag is flipped and tests start failing.
     */
    fun setDefault(flag: BooleanFlag) = booleanFlags.putIfAbsent(flag.name, flag.default)

    /**
     * Set the given flag's default value if no other value has been set.
     *
     * REMINDER: You should always test your code with your flag in both configurations, so
     * generally you should be setting a particular value. This method should be reserved for
     * situations where the flag needs to be read (e.g. in the class constructor), but its value
     * shouldn't affect the actual test cases. In those cases, it's mildly safer to use this method
     * than to hard-code `false` or `true` because then at least if you're wrong, and the flag value
     * *does* matter, you'll notice when the flag is flipped and tests start failing.
     */
    fun setDefault(flag: SysPropBooleanFlag) = booleanFlags.putIfAbsent(flag.name, flag.default)

    private fun notifyFlagChanged(flag: Flag<*>) {
        flagListeners[flag.name]?.let { listeners ->
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

    override fun isEnabled(flag: UnreleasedFlag): Boolean = requireBooleanValue(flag.name)

    override fun isEnabled(flag: ReleasedFlag): Boolean = requireBooleanValue(flag.name)

    override fun isEnabled(flag: ResourceBooleanFlag): Boolean = requireBooleanValue(flag.name)

    override fun isEnabled(flag: SysPropBooleanFlag): Boolean = requireBooleanValue(flag.name)

    override fun getString(flag: StringFlag): String = requireStringValue(flag.name)

    override fun getString(flag: ResourceStringFlag): String = requireStringValue(flag.name)

    override fun getInt(flag: IntFlag): Int = requireIntValue(flag.name)

    override fun getInt(flag: ResourceIntFlag): Int = requireIntValue(flag.name)

    override fun addListener(flag: Flag<*>, listener: FlagListenable.Listener) {
        flagListeners.getOrPut(flag.name) { mutableSetOf() }.add(listener)
        listenerflagNames.getOrPut(listener) { mutableSetOf() }.add(flag.name)
    }

    override fun removeListener(listener: FlagListenable.Listener) {
        listenerflagNames.remove(listener)?.let { flagNames ->
            flagNames.forEach { id -> flagListeners[id]?.remove(listener) }
        }
    }

    override fun dump(writer: PrintWriter, args: Array<out String>?) {
        // no-op
    }

    private fun flagName(flagName: String): String {
        return knownFlagNames[flagName] ?: "UNKNOWN($flagName)"
    }

    private fun requireBooleanValue(flagName: String): Boolean {
        return booleanFlags[flagName]
            ?: error("Flag ${flagName(flagName)} was accessed as boolean but not specified.")
    }

    private fun requireStringValue(flagName: String): String {
        return stringFlags[flagName]
            ?: error("Flag ${flagName(flagName)} was accessed as string but not specified.")
    }

    private fun requireIntValue(flagName: String): Int {
        return intFlags[flagName]
            ?: error("Flag ${flagName(flagName)} was accessed as int but not specified.")
    }
}

@Module(includes = [FakeFeatureFlagsClassicModule.Bindings::class])
class FakeFeatureFlagsClassicModule(
    @get:Provides val fakeFeatureFlagsClassic: FakeFeatureFlagsClassic = FakeFeatureFlagsClassic(),
) {

    constructor(
        block: FakeFeatureFlagsClassic.() -> Unit
    ) : this(FakeFeatureFlagsClassic().apply(block))

    @Module
    interface Bindings {
        @Binds fun bindFake(fake: FakeFeatureFlagsClassic): FeatureFlagsClassic
        @Binds fun bindClassic(classic: FeatureFlagsClassic): FeatureFlags
        @Binds fun bindFakeClassic(fake: FakeFeatureFlagsClassic): FakeFeatureFlags
    }
}

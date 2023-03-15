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

import android.annotation.BoolRes

object FlagsFactory {
    private val flagMap = mutableMapOf<String, Flag<*>>()

    val knownFlags: Map<String, Flag<*>>
        get() {
            // We need to access Flags in order to initialize our map.
            assert(flagMap.contains(Flags.TEAMFOOD.name)) { "Where is teamfood?" }
            return flagMap
        }

    fun unreleasedFlag(
        id: Int,
        name: String,
        namespace: String = "systemui",
        teamfood: Boolean = false
    ): UnreleasedFlag {
        val flag = UnreleasedFlag(id = id, name = name, namespace = namespace, teamfood = teamfood)
        FlagsFactory.checkForDupesAndAdd(flag)
        return flag
    }

    fun releasedFlag(
        id: Int,
        name: String,
        namespace: String = "systemui",
        teamfood: Boolean = false
    ): ReleasedFlag {
        val flag = ReleasedFlag(id = id, name = name, namespace = namespace, teamfood = teamfood)
        FlagsFactory.checkForDupesAndAdd(flag)
        return flag
    }

    fun resourceBooleanFlag(
        id: Int,
        @BoolRes resourceId: Int,
        name: String,
        namespace: String = "systemui",
        teamfood: Boolean = false
    ): ResourceBooleanFlag {
        val flag =
            ResourceBooleanFlag(
                id = id,
                name = name,
                namespace = namespace,
                resourceId = resourceId,
                teamfood = teamfood
            )
        FlagsFactory.checkForDupesAndAdd(flag)
        return flag
    }

    fun sysPropBooleanFlag(
        id: Int,
        name: String,
        namespace: String = "systemui",
        default: Boolean = false
    ): SysPropBooleanFlag {
        val flag =
            SysPropBooleanFlag(id = id, name = name, namespace = "systemui", default = default)
        FlagsFactory.checkForDupesAndAdd(flag)
        return flag
    }

    private fun checkForDupesAndAdd(flag: Flag<*>) {
        if (flagMap.containsKey(flag.name)) {
            throw IllegalArgumentException("Name {flag.name} is already registered")
        }
        flagMap.forEach {
            if (it.value.id == flag.id) {
                throw IllegalArgumentException("Name {flag.id} is already registered")
            }
        }
        flagMap[flag.name] = flag
    }
}

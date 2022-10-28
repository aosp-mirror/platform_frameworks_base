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

import com.android.systemui.util.DeviceConfigProxy
import dagger.Binds
import dagger.Module
import javax.inject.Inject

interface ServerFlagReader {
    /** Returns true if there is a server-side setting stored. */
    fun hasOverride(flagId: Int): Boolean

    /** Returns any stored server-side setting or the default if not set. */
    fun readServerOverride(flagId: Int, default: Boolean): Boolean
}

class ServerFlagReaderImpl @Inject constructor(
    private val deviceConfig: DeviceConfigProxy
) : ServerFlagReader {
    override fun hasOverride(flagId: Int): Boolean =
        deviceConfig.getProperty(
            SYSUI_NAMESPACE,
            getServerOverrideName(flagId)
        ) != null

    override fun readServerOverride(flagId: Int, default: Boolean): Boolean {
        return deviceConfig.getBoolean(
            SYSUI_NAMESPACE,
            getServerOverrideName(flagId),
            default
        )
    }

    private fun getServerOverrideName(flagId: Int): String {
        return "flag_override_$flagId"
    }
}

private val SYSUI_NAMESPACE = "systemui"

@Module
interface ServerFlagReaderModule {
    @Binds
    fun bindsReader(impl: ServerFlagReaderImpl): ServerFlagReader
}

class ServerFlagReaderFake : ServerFlagReader {
    private val flagMap: MutableMap<Int, Boolean> = mutableMapOf()

    override fun hasOverride(flagId: Int): Boolean {
        return flagMap.containsKey(flagId)
    }

    override fun readServerOverride(flagId: Int, default: Boolean): Boolean {
        return flagMap.getOrDefault(flagId, default)
    }

    fun setFlagValue(flagId: Int, value: Boolean) {
        flagMap.put(flagId, value)
    }

    fun eraseFlag(flagId: Int) {
        flagMap.remove(flagId)
    }
}

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

import android.provider.DeviceConfig
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.DeviceConfigProxy
import dagger.Module
import dagger.Provides
import java.util.concurrent.Executor
import javax.inject.Inject

interface ServerFlagReader {
    /** Returns true if there is a server-side setting stored. */
    fun hasOverride(flagId: Int): Boolean

    /** Returns any stored server-side setting or the default if not set. */
    fun readServerOverride(flagId: Int, default: Boolean): Boolean

    /** Register a listener for changes to any of the passed in flags. */
    fun listenForChanges(values: Collection<Flag<*>>, listener: ChangeListener)

    interface ChangeListener {
        fun onChange()
    }
}

class ServerFlagReaderImpl @Inject constructor(
    private val namespace: String,
    private val deviceConfig: DeviceConfigProxy,
    @Background private val executor: Executor
) : ServerFlagReader {

    private val listeners =
        mutableListOf<Pair<ServerFlagReader.ChangeListener, Collection<Flag<*>>>>()

    private val onPropertiesChangedListener = object : DeviceConfig.OnPropertiesChangedListener {
        override fun onPropertiesChanged(properties: DeviceConfig.Properties) {
            if (properties.namespace != namespace) {
                return
            }

            for ((listener, flags) in listeners) {
                propLoop@ for (propName in properties.keyset) {
                    for (flag in flags) {
                        if (propName == getServerOverrideName(flag.id)) {
                            listener.onChange()
                            break@propLoop
                        }
                    }
                }
            }
        }
    }

    override fun hasOverride(flagId: Int): Boolean =
        deviceConfig.getProperty(
            namespace,
            getServerOverrideName(flagId)
        ) != null

    override fun readServerOverride(flagId: Int, default: Boolean): Boolean {
        return deviceConfig.getBoolean(
            namespace,
            getServerOverrideName(flagId),
            default
        )
    }

    override fun listenForChanges(
        flags: Collection<Flag<*>>,
        listener: ServerFlagReader.ChangeListener
    ) {
        if (listeners.isEmpty()) {
            deviceConfig.addOnPropertiesChangedListener(
                namespace,
                executor,
                onPropertiesChangedListener
            )
        }
        listeners.add(Pair(listener, flags))
    }

    private fun getServerOverrideName(flagId: Int): String {
        return "flag_override_$flagId"
    }
}

@Module
interface ServerFlagReaderModule {
    companion object {
        private val SYSUI_NAMESPACE = "systemui"

        @JvmStatic
        @Provides
        @SysUISingleton
        fun bindsReader(
            deviceConfig: DeviceConfigProxy,
            @Background executor: Executor
        ): ServerFlagReader {
            return ServerFlagReaderImpl(
                SYSUI_NAMESPACE, deviceConfig, executor
            )
        }
    }
}

class ServerFlagReaderFake : ServerFlagReader {
    private val flagMap: MutableMap<Int, Boolean> = mutableMapOf()
    private val listeners =
        mutableListOf<Pair<ServerFlagReader.ChangeListener, Collection<Flag<*>>>>()

    override fun hasOverride(flagId: Int): Boolean {
        return flagMap.containsKey(flagId)
    }

    override fun readServerOverride(flagId: Int, default: Boolean): Boolean {
        return flagMap.getOrDefault(flagId, default)
    }

    fun setFlagValue(flagId: Int, value: Boolean) {
        flagMap.put(flagId, value)

        for ((listener, flags) in listeners) {
            flagLoop@ for (flag in flags) {
                if (flagId == flag.id) {
                    listener.onChange()
                    break@flagLoop
                }
            }
        }
    }

    fun eraseFlag(flagId: Int) {
        flagMap.remove(flagId)
    }

    override fun listenForChanges(
        flags: Collection<Flag<*>>,
        listener: ServerFlagReader.ChangeListener
    ) {
    }
}

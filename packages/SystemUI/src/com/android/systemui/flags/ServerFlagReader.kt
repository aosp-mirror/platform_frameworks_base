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
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.TestHarness
import com.android.systemui.util.DeviceConfigProxy
import dagger.Module
import dagger.Provides
import java.util.concurrent.Executor
import javax.inject.Inject

interface ServerFlagReader {
    /** Returns true if there is a server-side setting stored. */
    fun hasOverride(namespace: String, name: String): Boolean

    /** Returns any stored server-side setting or the default if not set. */
    fun readServerOverride(namespace: String, name: String, default: Boolean): Boolean
    /** Register a listener for changes to any of the passed in flags. */
    fun listenForChanges(values: Collection<Flag<*>>, listener: ChangeListener)

    interface ChangeListener {
        fun onChange(flag: Flag<*>, value: String?)
    }
}

class ServerFlagReaderImpl @Inject constructor(
    private val namespace: String,
    private val deviceConfig: DeviceConfigProxy,
    @Background private val executor: Executor,
    @TestHarness private val isTestHarness: Boolean
) : ServerFlagReader {

    private val TAG = "ServerFlagReader"

    private val listeners =
        mutableListOf<Pair<ServerFlagReader.ChangeListener, Collection<Flag<*>>>>()

    private val onPropertiesChangedListener = object : DeviceConfig.OnPropertiesChangedListener {
        override fun onPropertiesChanged(properties: DeviceConfig.Properties) {
            if (isTestHarness) {
                Log.w(TAG, "Ignore server flag changes in Test Harness mode.")
                return
            }
            if (properties.namespace != namespace) {
                return
            }

            for ((listener, flags) in listeners) {
                propLoop@ for (propName in properties.keyset) {
                    for (flag in flags) {
                        if (propName == flag.name) {
                            listener.onChange(flag, properties.getString(propName, null))
                            break@propLoop
                        }
                    }
                }
            }
        }
    }

    override fun hasOverride(namespace: String, name: String): Boolean =
        !namespace.isBlank() && !name.isBlank() && deviceConfig.getProperty(
            namespace,
            name
        ) != null


    override fun readServerOverride(namespace: String, name: String, default: Boolean): Boolean =
        !namespace.isBlank() && !name.isBlank() && deviceConfig.getBoolean(
            namespace,
            name,
            default
        )

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
            @Background executor: Executor,
            @TestHarness isTestHarness: Boolean
        ): ServerFlagReader {
            return ServerFlagReaderImpl(
                SYSUI_NAMESPACE, deviceConfig, executor, isTestHarness
            )
        }
    }
}

class ServerFlagReaderFake : ServerFlagReader {
    private val flagMap: MutableMap<String, Boolean> = mutableMapOf()
    private val listeners =
        mutableListOf<Pair<ServerFlagReader.ChangeListener, Collection<Flag<*>>>>()

    override fun hasOverride(namespace: String, name: String): Boolean {
        return flagMap.containsKey(name)
    }

    override fun readServerOverride(namespace: String, name: String, default: Boolean): Boolean {
        return flagMap.getOrDefault(name, default)
    }

    fun setFlagValue(namespace: String, name: String, value: Boolean) {
        flagMap.put(name, value)

        for ((listener, flags) in listeners) {
            flagLoop@ for (flag in flags) {
                if (name == flag.name) {
                    listener.onChange(flag, if (value) "true" else "false")
                    break@flagLoop
                }
            }
        }
    }

    fun eraseFlag(namespace: String, name: String) {
        flagMap.remove(name)
    }

    override fun listenForChanges(
        flags: Collection<Flag<*>>,
        listener: ServerFlagReader.ChangeListener
    ) {
        listeners.add(Pair(listener, flags))
    }
}

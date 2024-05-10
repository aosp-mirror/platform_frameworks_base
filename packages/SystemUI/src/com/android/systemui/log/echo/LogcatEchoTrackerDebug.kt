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

package com.android.systemui.log.echo

import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogcatEchoTracker
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.echo.LogcatEchoTrackerCommand.Companion.ECHO_TRACKER_COMMAND_NAME
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.settings.GlobalSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

/**
 * A version of [LogcatEchoTracker] that supports fine-grained echoing of log messages to logcat,
 * filtered by buffer, tag, and log level.
 *
 * Filters can be added and removed via a shell command (`adb shell cmd statusbar echo`). See
 * [LogcatEchoTrackerCommand] for details.
 *
 * Note that some log messages may fail to be echoed while the systemui process is first starting
 * up, before we load the echo settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogcatEchoTrackerDebug
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background backgroundDispatcher: CoroutineDispatcher,
    private val globalSettings: GlobalSettings,
    private val commandRegistry: CommandRegistry,
) : LogcatEchoTracker {

    // This class uses a single-writer, many-readers pattern that allows us to avoid the need for
    // locking. In this case, this means that our shared state (the override maps) can be _read_ by
    // any number of threads, but they're always written to by a single thread (dispatched by
    // sequentialBgDispatcher). Such a pattern allows us to use the more performant @Volatile below
    // instead of synchronization locks.
    //
    // Okay: some of what I just told you is a lie. sequentialBgDispatcher does not dispatch to a
    // single thread. Instead, it guarantees that all work it schedules is _sequential_, meaning
    // that Job B cannot start until Job A ends (this is actually a stronger guarantee than single-
    // threaded execution due to the possibility of suspend functions). Because
    // sequentialBgDispatcher is dispatching from the Dispatchers.IO thread pool, each individual
    // "write" job might run on a different thread from that pool. However, because we are
    // enforcing sequential execution, exactly which thread an individual write job runs on doesn't
    // matter.
    private val sequentialBgDispatcher = backgroundDispatcher.limitedParallelism(1)

    // Okay. So. Why are these @Volatile. We've eliminated the need for synchronization primitives,
    // but now we must contend with thread memory caching. Without an explicit synchronization
    // signal, other threads may see "stale" versions of our state when they try to read from these
    // maps, even after they've been updated by the writer thread. @Volatile solves this problem:
    // it eliminates the possibility of stale reads while still being much more performant than
    // locking.
    @Volatile private var bufferOverrides = mapOf<String, LogLevel>()
    @Volatile private var tagOverrides = mapOf<String, LogLevel>()

    private val settingFormat = LogcatEchoSettingFormat()

    fun start() {
        loadEchoOverrides()

        commandRegistry.registerCommand(ECHO_TRACKER_COMMAND_NAME) {
            LogcatEchoTrackerCommand(this)
        }
    }

    override fun isBufferLoggable(bufferName: String, level: LogLevel): Boolean {
        return level >= (bufferOverrides[bufferName] ?: DEFAULT_LOG_LEVEL)
    }

    override fun isTagLoggable(tagName: String, level: LogLevel): Boolean {
        return level >= (tagOverrides[tagName] ?: DEFAULT_LOG_LEVEL)
    }

    fun listEchoOverrides(): List<LogcatEchoOverride> {
        val list = mutableListOf<LogcatEchoOverride>()

        val frozenBufferOverrides = bufferOverrides
        val frozenTagOverrides = tagOverrides

        for ((name, level) in frozenBufferOverrides) {
            list.add(LogcatEchoOverride(EchoOverrideType.BUFFER, name, level))
        }
        for ((name, level) in frozenTagOverrides) {
            list.add(LogcatEchoOverride(EchoOverrideType.TAG, name, level))
        }
        return list
    }

    fun setEchoLevel(type: EchoOverrideType, name: String, level: LogLevel?) {
        applicationScope.launch(sequentialBgDispatcher) {
            val newBufferOverrides = bufferOverrides.toMutableMap()
            val newTagOverrides = tagOverrides.toMutableMap()

            val mutatedMap =
                when (type) {
                    EchoOverrideType.BUFFER -> newBufferOverrides
                    EchoOverrideType.TAG -> newTagOverrides
                }
            if (level != null) {
                mutatedMap[name] = level
            } else {
                mutatedMap.remove(name)
            }

            bufferOverrides = newBufferOverrides
            tagOverrides = newTagOverrides

            val list = listEchoOverrides()
            globalSettings.putString(OVERRIDE_SETTING_PATH, settingFormat.stringifyOverrides(list))
        }
    }

    fun clearAllOverrides() {
        applicationScope.launch(sequentialBgDispatcher) {
            bufferOverrides = emptyMap()
            tagOverrides = emptyMap()

            val list = listEchoOverrides()
            globalSettings.putString(OVERRIDE_SETTING_PATH, settingFormat.stringifyOverrides(list))
        }
    }

    private fun loadEchoOverrides() {
        applicationScope.launch(sequentialBgDispatcher) {
            val overrideSetting = globalSettings.getString(OVERRIDE_SETTING_PATH) ?: return@launch
            val overrideList = settingFormat.parseOverrides(overrideSetting)

            val newBufferOverrides = mutableMapOf<String, LogLevel>()
            val newTagOverrides = mutableMapOf<String, LogLevel>()

            for (override in overrideList) {
                val map =
                    when (override.type) {
                        EchoOverrideType.BUFFER -> newBufferOverrides
                        EchoOverrideType.TAG -> newTagOverrides
                    }
                map[override.name] = override.level
            }

            bufferOverrides = newBufferOverrides
            tagOverrides = newTagOverrides
        }
    }
}

enum class EchoOverrideType {
    BUFFER,
    TAG,
}

private const val TAG = "LogcatEchoTrackerDebug"

private const val OVERRIDE_SETTING_PATH = "systemui/logbuffer_echo_overrides"

private val DEFAULT_LOG_LEVEL = LogLevel.WARNING

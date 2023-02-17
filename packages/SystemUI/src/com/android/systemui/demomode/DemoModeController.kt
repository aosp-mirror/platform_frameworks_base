/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.demomode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import com.android.systemui.Dumpable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.demomode.DemoMode.ACTION_DEMO
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.util.Assert
import com.android.systemui.util.settings.GlobalSettings
import java.io.PrintWriter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/**
 * Handles system broadcasts for [DemoMode]
 *
 * Injected via [DemoModeModule]
 */
class DemoModeController
constructor(
    private val context: Context,
    private val dumpManager: DumpManager,
    private val globalSettings: GlobalSettings,
    private val broadcastDispatcher: BroadcastDispatcher,
) : CallbackController<DemoMode>, Dumpable {

    // Var updated when the availability tracker changes, or when we enter/exit demo mode in-process
    var isInDemoMode = false

    var isAvailable = false
        get() = tracker.isDemoModeAvailable

    private var initialized = false

    private val receivers = mutableListOf<DemoMode>()
    private val receiverMap: Map<String, MutableList<DemoMode>>

    init {
        // Don't persist demo mode across restarts.
        requestFinishDemoMode()

        val m = mutableMapOf<String, MutableList<DemoMode>>()
        DemoMode.COMMANDS.map { command -> m.put(command, mutableListOf()) }
        receiverMap = m
    }

    fun initialize() {
        if (initialized) {
            throw IllegalStateException("Already initialized")
        }

        initialized = true

        dumpManager.registerNormalDumpable(TAG, this)

        // Due to DemoModeFragment running in systemui:tuner process, we have to observe for
        // content changes to know if the setting turned on or off
        tracker.startTracking()

        isInDemoMode = tracker.isInDemoMode

        val demoFilter = IntentFilter()
        demoFilter.addAction(ACTION_DEMO)

        broadcastDispatcher.registerReceiver(
            receiver = broadcastReceiver,
            filter = demoFilter,
            user = UserHandle.ALL,
            permission = android.Manifest.permission.DUMP,
        )
    }

    override fun addCallback(listener: DemoMode) {
        // Register this listener for its commands
        val commands = listener.demoCommands()

        commands.forEach { command ->
            if (!receiverMap.containsKey(command)) {
                throw IllegalStateException(
                    "Command ($command) not recognized. " + "See DemoMode.java for valid commands"
                )
            }

            receiverMap[command]!!.add(listener)
        }

        synchronized(this) { receivers.add(listener) }

        if (isInDemoMode) {
            listener.onDemoModeStarted()
        }
    }

    override fun removeCallback(listener: DemoMode) {
        synchronized(this) {
            listener.demoCommands().forEach { command -> receiverMap[command]!!.remove(listener) }

            receivers.remove(listener)
        }
    }

    /**
     * Create a [Flow] for the stream of demo mode arguments that come in for the given [command]
     *
     * This is equivalent of creating a listener manually and adding an event handler for the given
     * command, like so:
     *
     * ```
     * class Demoable {
     *   private val demoHandler = object : DemoMode {
     *     override fun demoCommands() = listOf(<command>)
     *
     *     override fun dispatchDemoCommand(command: String, args: Bundle) {
     *       handleDemoCommand(args)
     *     }
     *   }
     * }
     * ```
     *
     * @param command The top-level demo mode command you want a stream for
     */
    fun demoFlowForCommand(command: String): Flow<Bundle> = conflatedCallbackFlow {
        val callback =
            object : DemoMode {
                override fun demoCommands(): List<String> = listOf(command)

                override fun dispatchDemoCommand(command: String, args: Bundle) {
                    trySend(args)
                }
            }

        addCallback(callback)
        awaitClose { removeCallback(callback) }
    }

    private fun setIsDemoModeAllowed(enabled: Boolean) {
        // Turn off demo mode if it was on
        if (isInDemoMode && !enabled) {
            requestFinishDemoMode()
        }
    }

    private fun enterDemoMode() {
        isInDemoMode = true
        Assert.isMainThread()

        val copy: List<DemoModeCommandReceiver>
        synchronized(this) { copy = receivers.toList() }

        copy.forEach { r -> r.onDemoModeStarted() }
    }

    private fun exitDemoMode() {
        isInDemoMode = false
        Assert.isMainThread()

        val copy: List<DemoModeCommandReceiver>
        synchronized(this) { copy = receivers.toList() }

        copy.forEach { r -> r.onDemoModeFinished() }
    }

    fun dispatchDemoCommand(command: String, args: Bundle) {
        Assert.isMainThread()
        if (DEBUG) {
            Log.d(TAG, "dispatchDemoCommand: $command, args=$args")
        }

        if (!isAvailable) {
            return
        }

        if (command == DemoMode.COMMAND_ENTER) {
            enterDemoMode()
        } else if (command == DemoMode.COMMAND_EXIT) {
            exitDemoMode()
        } else if (!isInDemoMode) {
            enterDemoMode()
        }

        // See? demo mode is easy now, you just notify the listeners when their command is called
        receiverMap[command]!!.forEach { receiver -> receiver.dispatchDemoCommand(command, args) }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("DemoModeController state -")
        pw.println("  isInDemoMode=$isInDemoMode")
        pw.println("  isDemoModeAllowed=$isAvailable")
        pw.print("  receivers=[")
        val copy: List<DemoModeCommandReceiver>
        synchronized(this) { copy = receivers.toList() }
        copy.forEach { recv -> pw.print(" ${recv.javaClass.simpleName}") }
        pw.println(" ]")
        pw.println("  receiverMap= [")
        receiverMap.keys.forEach { command ->
            pw.print("    $command : [")
            val recvs =
                receiverMap[command]!!
                    .map { receiver -> receiver.javaClass.simpleName }
                    .joinToString(",")
            pw.println("$recvs ]")
        }
    }

    private val tracker =
        object : DemoModeAvailabilityTracker(context, globalSettings) {
            override fun onDemoModeAvailabilityChanged() {
                setIsDemoModeAllowed(isDemoModeAvailable)
            }

            override fun onDemoModeStarted() {
                if (this@DemoModeController.isInDemoMode != isInDemoMode) {
                    enterDemoMode()
                }
            }

            override fun onDemoModeFinished() {
                if (this@DemoModeController.isInDemoMode != isInDemoMode) {
                    exitDemoMode()
                }
            }
        }

    private val broadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (DEBUG) {
                    Log.v(TAG, "onReceive: $intent")
                }

                val action = intent.action
                if (!ACTION_DEMO.equals(action)) {
                    return
                }

                val bundle = intent.extras ?: return
                val command = bundle.getString("command", "").trim().lowercase()
                if (command.isEmpty()) {
                    return
                }

                try {
                    dispatchDemoCommand(command, bundle)
                } catch (t: Throwable) {
                    Log.w(TAG, "Error running demo command, intent=$intent $t")
                }
            }
        }

    fun requestSetDemoModeAllowed(allowed: Boolean) {
        setGlobal(DEMO_MODE_ALLOWED, if (allowed) 1 else 0)
    }

    fun requestStartDemoMode() {
        setGlobal(DEMO_MODE_ON, 1)
    }

    fun requestFinishDemoMode() {
        setGlobal(DEMO_MODE_ON, 0)
    }

    private fun setGlobal(key: String, value: Int) {
        globalSettings.putInt(key, value)
    }

    companion object {
        const val DEMO_MODE_ALLOWED = "sysui_demo_allowed"
        const val DEMO_MODE_ON = "sysui_tuner_demo_on"
    }
}

private const val TAG = "DemoModeController"
private const val DEBUG = false

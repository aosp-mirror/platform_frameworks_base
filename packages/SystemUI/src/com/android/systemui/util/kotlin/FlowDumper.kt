/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.util.kotlin

import android.util.IndentingPrintWriter
import com.android.systemui.Dumpable
import com.android.systemui.dump.DumpManager
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printCollection
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * An interface which gives the implementing type flow extension functions which will register a
 * given flow as a field in the Dumpable.
 */
interface FlowDumper : Dumpable {
    /**
     * Include the last emitted value of this Flow whenever it is being collected. Remove its value
     * when collection ends.
     *
     * @param dumpName the name to use for this field in the dump output
     */
    fun <T> Flow<T>.dumpWhileCollecting(dumpName: String): Flow<T>

    /**
     * Include the [SharedFlow.replayCache] for this Flow in the dump.
     *
     * @param dumpName the name to use for this field in the dump output
     */
    fun <T, F : SharedFlow<T>> F.dumpReplayCache(dumpName: String): F

    /**
     * Include the [StateFlow.value] for this Flow in the dump.
     *
     * @param dumpName the name to use for this field in the dump output
     */
    fun <T, F : StateFlow<T>> F.dumpValue(dumpName: String): F

    /** The default [Dumpable.dump] implementation which just calls [dumpFlows] */
    override fun dump(pw: PrintWriter, args: Array<out String>) = dumpFlows(pw.asIndenting())

    /** Dump all the values from any registered / active Flows. */
    fun dumpFlows(pw: IndentingPrintWriter)
}

/**
 * The minimal implementation of FlowDumper. The owner must either register this with the
 * DumpManager, or else call [dumpFlows] from its own [Dumpable.dump] method.
 */
open class SimpleFlowDumper : FlowDumper {

    private val stateFlowMap = ConcurrentHashMap<String, StateFlow<*>>()
    private val sharedFlowMap = ConcurrentHashMap<String, SharedFlow<*>>()
    private val flowCollectionMap = ConcurrentHashMap<Pair<String, String>, Any>()

    protected fun isNotEmpty(): Boolean =
        stateFlowMap.isNotEmpty() || sharedFlowMap.isNotEmpty() || flowCollectionMap.isNotEmpty()

    protected open fun onMapKeysChanged(added: Boolean) {}

    override fun dumpFlows(pw: IndentingPrintWriter) {
        pw.printCollection("StateFlow (value)", stateFlowMap.toSortedMap().entries) { (key, flow) ->
            append(key).append('=').println(flow.value)
        }
        pw.printCollection("SharedFlow (replayCache)", sharedFlowMap.toSortedMap().entries) {
            (key, flow) ->
            append(key).append('=').println(flow.replayCache)
        }
        val comparator = compareBy<Pair<String, String>> { it.first }.thenBy { it.second }
        pw.printCollection("Flow (latest)", flowCollectionMap.toSortedMap(comparator).entries) {
            (pair, value) ->
            append(pair.first).append('=').println(value)
        }
    }

    override fun <T> Flow<T>.dumpWhileCollecting(dumpName: String): Flow<T> = flow {
        val mapKey = dumpName to idString
        try {
            collect {
                flowCollectionMap[mapKey] = it ?: "null"
                onMapKeysChanged(added = true)
                emit(it)
            }
        } finally {
            flowCollectionMap.remove(mapKey)
            onMapKeysChanged(added = false)
        }
    }

    override fun <T, F : StateFlow<T>> F.dumpValue(dumpName: String): F {
        stateFlowMap[dumpName] = this
        onMapKeysChanged(added = true)
        return this
    }

    override fun <T, F : SharedFlow<T>> F.dumpReplayCache(dumpName: String): F {
        sharedFlowMap[dumpName] = this
        onMapKeysChanged(added = true)
        return this
    }

    protected val Any.idString: String
        get() = Integer.toHexString(System.identityHashCode(this))
}

/**
 * An implementation of [FlowDumper] that registers itself whenever there is something to dump. This
 * class is meant to be extended.
 *
 * @param dumpManager this will be used by the [FlowDumperImpl] to register and unregister itself
 *   when there is something to dump.
 * @param tag a static name by which this [FlowDumperImpl] is registered. If not provided, this
 *   class's name will be used.
 */
abstract class FlowDumperImpl(
    private val dumpManager: DumpManager,
    private val tag: String? = null,
) : SimpleFlowDumper() {

    override fun onMapKeysChanged(added: Boolean) {
        updateRegistration(required = added)
    }

    private val dumpManagerName = "[$idString] ${tag ?: javaClass.simpleName}"

    private var registered = AtomicBoolean(false)

    private fun updateRegistration(required: Boolean) {
        if (required && registered.get()) return
        synchronized(registered) {
            val shouldRegister = isNotEmpty()
            val wasRegistered = registered.getAndSet(shouldRegister)
            if (wasRegistered != shouldRegister) {
                if (shouldRegister) {
                    dumpManager.registerCriticalDumpable(dumpManagerName, this)
                } else {
                    dumpManager.unregisterDumpable(dumpManagerName)
                }
            }
        }
    }
}

/**
 * A [FlowDumper] that also has an [activateFlowDumper] suspend function that allows the dumper to
 * be registered with the [DumpManager] only when activated, just like
 * [Activatable.activate()][com.android.systemui.lifecycle.Activatable.activate].
 */
interface ActivatableFlowDumper : FlowDumper {
    suspend fun activateFlowDumper(): Nothing
}

/**
 * Implementation of [ActivatableFlowDumper] that only registers when activated.
 *
 * This is generally used to implement [ActivatableFlowDumper] by delegation, especially for
 * [SysUiViewModel] implementations.
 *
 * @param dumpManager used to automatically register and unregister this instance when activated and
 *   there is something to dump.
 * @param tag the name with which this is dumper registered.
 */
class ActivatableFlowDumperImpl(
    private val dumpManager: DumpManager,
    tag: String,
) : SimpleFlowDumper(), ActivatableFlowDumper {

    private val registration =
        object : ExclusiveActivatable() {
            override suspend fun onActivated(): Nothing {
                try {
                    dumpManager.registerCriticalDumpable(
                        dumpManagerName,
                        this@ActivatableFlowDumperImpl
                    )
                    awaitCancellation()
                } finally {
                    dumpManager.unregisterDumpable(dumpManagerName)
                }
            }
        }

    private val dumpManagerName = "[$idString] $tag"

    override suspend fun activateFlowDumper(): Nothing {
        registration.activate()
    }
}

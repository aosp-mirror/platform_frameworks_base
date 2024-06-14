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
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printCollection
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 * An implementation of [FlowDumper]. This be extended directly, or can be used to implement
 * [FlowDumper] by delegation.
 *
 * @param dumpManager if provided, this will be used by the [FlowDumperImpl] to register and
 *   unregister itself when there is something to dump.
 * @param tag a static name by which this [FlowDumperImpl] is registered. If not provided, this
 *   class's name will be used. If you're implementing by delegation, you probably want to provide
 *   this tag to get a meaningful dumpable name.
 */
open class FlowDumperImpl(private val dumpManager: DumpManager?, tag: String? = null) : FlowDumper {
    private val stateFlowMap = ConcurrentHashMap<String, StateFlow<*>>()
    private val sharedFlowMap = ConcurrentHashMap<String, SharedFlow<*>>()
    private val flowCollectionMap = ConcurrentHashMap<Pair<String, String>, Any>()
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

    private val Any.idString: String
        get() = Integer.toHexString(System.identityHashCode(this))

    override fun <T> Flow<T>.dumpWhileCollecting(dumpName: String): Flow<T> = flow {
        val mapKey = dumpName to idString
        try {
            collect {
                flowCollectionMap[mapKey] = it ?: "null"
                updateRegistration(required = true)
                emit(it)
            }
        } finally {
            flowCollectionMap.remove(mapKey)
            updateRegistration(required = false)
        }
    }

    override fun <T, F : StateFlow<T>> F.dumpValue(dumpName: String): F {
        stateFlowMap[dumpName] = this
        return this
    }

    override fun <T, F : SharedFlow<T>> F.dumpReplayCache(dumpName: String): F {
        sharedFlowMap[dumpName] = this
        return this
    }

    private val dumpManagerName = tag ?: "[$idString] ${javaClass.simpleName}"
    private var registered = AtomicBoolean(false)
    private fun updateRegistration(required: Boolean) {
        if (dumpManager == null) return
        if (required && registered.get()) return
        synchronized(registered) {
            val shouldRegister =
                stateFlowMap.isNotEmpty() ||
                    sharedFlowMap.isNotEmpty() ||
                    flowCollectionMap.isNotEmpty()
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

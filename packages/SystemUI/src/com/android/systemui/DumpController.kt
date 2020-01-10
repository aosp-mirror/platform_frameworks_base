/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui

import android.util.ArraySet
import android.util.Log
import androidx.annotation.GuardedBy
import com.android.internal.util.Preconditions
import java.io.FileDescriptor
import java.io.PrintWriter
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

// TODO: Move all Dumpable dependencies to use DumpController
/**
 * Controller that allows any [Dumpable] to subscribe and be dumped along with other SystemUI
 * dependencies.
 *
 * To dump a specific dumpable on-demand:
 *
 * ```
 * $ adb shell dumpsys activity service com.android.systemui/.SystemUIService dependency DumpController <tag1>,<tag2>,<tag3>
 * ```
 *
 * Where tag1, tag2, etc. are the tags of the dumpables you want to dump.
 */
@Singleton
class DumpController @Inject constructor() : Dumpable {

    companion object {
        private const val TAG = "DumpController"
        private const val DEBUG = false
    }

    @GuardedBy("listeners")
    private val listeners = mutableListOf<RegisteredDumpable>()
    val numListeners: Int
        get() = listeners.size

    /**
     * Adds a [Dumpable] dumpable to be dumped.
     *
     * @param dumpable the [Dumpable] to be added
     */
    fun registerDumpable(dumpable: Dumpable) {
        Preconditions.checkNotNull(dumpable, "The dumpable to be added cannot be null")
        registerDumpable(dumpable.javaClass.simpleName, dumpable)
    }

    /**
     * Adds a [Dumpable] dumpable to be dumped.
     *
     * @param tag a string tag to associate with this dumpable. Tags must be globally unique; this
     *      method will throw if the same tag has already been registered. Tags can be used to
     *      filter output when debugging.
     * @param dumpable the [Dumpable] to be added
     */
    fun registerDumpable(tag: String, dumpable: Dumpable) {
        Preconditions.checkNotNull(dumpable, "The dumpable to be added cannot be null")
        if (DEBUG) Log.v(TAG, "*** register callback for $dumpable")
        synchronized<Unit>(listeners) {
            if (listeners.any { it.tag == tag }) {
                throw IllegalArgumentException("Duplicate dumpable tag registered: $tag")
            } else {
                listeners.add(RegisteredDumpable(tag, WeakReference(dumpable)))
            }
        }
    }

    /**
     * Removes a dumpable from the list of elements to be dumped.
     *
     * @param dumpable the [Dumpable] to be removed.
     */
    fun unregisterDumpable(dumpable: Dumpable) {
        if (DEBUG) Log.v(TAG, "*** unregister callback for $dumpable")
        synchronized(listeners) {
            listeners.removeAll { it.dumpable.get() == dumpable || it.dumpable.get() == null }
        }
    }

    /**
     * Dump all the [Dumpable] registered with the controller
     */
    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<String>) {
        pw.println("DumpController state:")

        val filter = if (args.size >= 3 && args[0].toLowerCase() == "dependency" &&
                args[1] == "DumpController") {
            ArraySet(args[2].split(',').map { it.toLowerCase() })
        } else {
            null
        }

        synchronized(listeners) {
            listeners.forEach {
                if (filter == null || filter.contains(it.tag.toLowerCase())) {
                    it.dumpable.get()?.dump(fd, pw, args)
                }
            }
        }
    }

    data class RegisteredDumpable(val tag: String, val dumpable: WeakReference<Dumpable>)
}

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
 */
@Singleton
class DumpController @Inject constructor() : Dumpable {

    companion object {
        private const val TAG = "DumpController"
        private const val DEBUG = false
    }

    @GuardedBy("listeners")
    private val listeners = mutableListOf<WeakReference<Dumpable>>()
    val numListeners: Int
        get() = listeners.size

    /**
     * Adds a [Dumpable] listener to be dumped. It will only be added if it is not already tracked.
     *
     * @param listener the [Dumpable] to be added
     */
    fun addListener(listener: Dumpable) {
        Preconditions.checkNotNull(listener, "The listener to be added cannot be null")
        if (DEBUG) Log.v(TAG, "*** register callback for $listener")
        synchronized<Unit>(listeners) {
            if (listeners.any { it.get() == listener }) {
                if (DEBUG) {
                    Log.e(TAG, "Object tried to add another callback")
                }
            } else {
                listeners.add(WeakReference(listener))
            }
        }
    }

    /**
     * Removes a listener from the list of elements to be dumped.
     *
     * @param listener the [Dumpable] to be removed.
     */
    fun removeListener(listener: Dumpable) {
        if (DEBUG) Log.v(TAG, "*** unregister callback for $listener")
        synchronized(listeners) {
            listeners.removeAll { it.get() == listener || it.get() == null }
        }
    }

    /**
     * Dump all the [Dumpable] registered with the controller
     */
    override fun dump(fd: FileDescriptor?, pw: PrintWriter, args: Array<String>?) {
        pw.println("DumpController state:")
        synchronized(listeners) {
            listeners.forEach { it.get()?.dump(fd, pw, args) }
        }
    }
}

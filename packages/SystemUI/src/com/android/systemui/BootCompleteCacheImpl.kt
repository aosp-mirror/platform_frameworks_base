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
 * limitations under the License.
 */

package com.android.systemui

import android.util.Log
import com.android.internal.annotations.GuardedBy
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Caches whether the device has reached [SystemService.PHASE_BOOT_COMPLETED].
 *
 * This class is constructed and set by [SystemUIApplication] and will notify all listeners when
 * boot is completed.
 */
@SysUISingleton
class BootCompleteCacheImpl @Inject constructor(dumpManager: DumpManager) :
        BootCompleteCache, Dumpable {

    companion object {
        private const val TAG = "BootCompleteCacheImpl"
        private const val DEBUG = false
    }

    init {
        dumpManager.registerDumpable(TAG, this)
    }

    @GuardedBy("listeners")
    private val listeners = mutableListOf<WeakReference<BootCompleteCache.BootCompleteListener>>()
    private val bootComplete = AtomicBoolean(false)

    /**
     * Provides the current boot state of the system as determined by [SystemUIApplication].
     * @return `true` if the system has reached [SystemService.PHASE_BOOT_COMPLETED]
     */
    override fun isBootComplete(): Boolean = bootComplete.get()

    /**
     * Indicates to this object that boot is complete. Subsequent calls to this function will have
     * no effect.
     */
    fun setBootComplete() {
        if (bootComplete.compareAndSet(false, true)) {
            if (DEBUG) Log.d(TAG, "Boot complete set")
            synchronized(listeners) {
                listeners.forEach {
                    it.get()?.onBootComplete()
                }
                listeners.clear()
            }
        }
    }

    /**
     * Add a listener for boot complete event. It will immediately return the current boot complete
     * state. If this value is true, [BootCompleteCache.BootCompleteListener.onBootComplete] will
     * never be called.
     *
     * @param listener a listener for boot complete state.
     * @return `true` if boot has been completed.
     */
    override fun addListener(listener: BootCompleteCache.BootCompleteListener): Boolean {
        if (bootComplete.get()) return true
        synchronized(listeners) {
            if (bootComplete.get()) return true
            listeners.add(WeakReference(listener))
            if (DEBUG) Log.d(TAG, "Adding listener: $listener")
            return false
        }
    }

    /**
     * Removes a listener for boot complete event.
     *
     * @param listener a listener to removed.
     */
    override fun removeListener(listener: BootCompleteCache.BootCompleteListener) {
        if (bootComplete.get()) return
        synchronized(listeners) {
            listeners.removeIf { it.get() == null || it.get() === listener }
            if (DEBUG) Log.d(TAG, "Removing listener: $listener")
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("BootCompleteCache state:")
        pw.println("  boot complete: ${isBootComplete()}")
        if (!isBootComplete()) {
            pw.println("  listeners:")
            synchronized(listeners) {
                listeners.forEach {
                    pw.println("    $it")
                }
            }
        }
    }
}
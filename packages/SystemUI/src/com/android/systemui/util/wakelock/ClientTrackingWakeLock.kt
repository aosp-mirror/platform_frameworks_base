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

package com.android.systemui.util.wakelock

import android.os.PowerManager
import android.util.Log
import com.android.systemui.util.wakelock.WakeLock.Builder.NO_TIMEOUT
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [PowerManager.WakeLock] wrapper that tracks acquire/release reasons and logs them if owning
 * logger is enabled.
 */
class ClientTrackingWakeLock(
    private val pmWakeLock: PowerManager.WakeLock,
    private val logger: WakeLockLogger?,
    private val maxTimeout: Long
) : WakeLock {

    private val activeClients = ConcurrentHashMap<String, AtomicInteger>()

    override fun acquire(why: String) {
        val count = activeClients.computeIfAbsent(why) { _ -> AtomicInteger(0) }.incrementAndGet()
        logger?.logAcquire(pmWakeLock, why, count)
        if (maxTimeout == NO_TIMEOUT) {
            pmWakeLock.acquire()
        } else {
            pmWakeLock.acquire(maxTimeout)
        }
    }

    override fun release(why: String) {
        val count = activeClients[why]?.decrementAndGet() ?: -1
        if (count < 0) {
            Log.wtf(WakeLock.TAG, "Releasing WakeLock with invalid reason: $why")
            // Restore count just in case.
            activeClients[why]?.incrementAndGet()
            return
        }

        logger?.logRelease(pmWakeLock, why, count)
        pmWakeLock.release()
    }

    override fun wrap(r: Runnable): Runnable = WakeLock.wrapImpl(this, r)

    fun activeClients(): Int =
        activeClients.reduceValuesToInt(Long.MAX_VALUE, AtomicInteger::get, 0, Integer::sum)

    override fun toString(): String {
        return "active clients=${activeClients()}"
    }
}

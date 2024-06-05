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

package com.android.systemui.statusbar.notification.row

import android.util.IndentingPrintWriter
import android.util.Log
import com.android.internal.util.LatencyTracker
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "BigPictureStatsManager"

@SysUISingleton
class BigPictureStatsManager
@Inject
constructor(
    private val latencyTracker: LatencyTracker,
    @Main private val mainDispatcher: CoroutineDispatcher,
    dumpManager: DumpManager
) : Dumpable {

    init {
        dumpManager.registerNormalDumpable(TAG, this)
    }

    private val startTimes = ConcurrentHashMap<String, Long>()
    private val durations = mutableListOf<Int>()
    private val lock = Any()
    suspend inline fun <T> measure(block: () -> T): T {
        val key = UUID.randomUUID().toString()
        onBegin(key)
        try {
            return block()
        } catch (t: Throwable) {
            onCancel(key)
            throw t
        } finally {
            onEnd(key)?.let { duration -> trackEvent(duration) }
        }
    }

    fun onBegin(key: String) {
        if (startTimes.contains(key)) {
            Log.wtf(TAG, "key $key is already in use")
            return
        }

        startTimes[key] = System.nanoTime()
    }

    fun onEnd(key: String): Int? {
        val startTime =
            startTimes.remove(key)
                ?: run {
                    Log.wtf(TAG, "No matching begin call for this $key")
                    return null
                }

        val durationInMillis = ((System.nanoTime() - startTime) / 1_000_000)
        return durationInMillis.toInt()
    }

    fun onCancel(key: String) {
        startTimes.remove(key)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        synchronized(lock) {
            if (durations.isEmpty()) {
                pw.println("No entries")
                return
            }

            val max = durations.max()
            val avg = durations.average().roundToInt()
            val p90 = percentile(durations, 90.0)
            val p99 = percentile(durations, 99.0)

            with(IndentingPrintWriter(pw)) {
                println("Lazy-loaded ${durations.size} images:")
                increaseIndent()
                println("Avg: $avg ms")
                println("Max: $max ms")
                println("P90: $p90 ms")
                println("P99: $p99 ms")
            }
        }
    }

    private fun percentile(times: List<Int>, percent: Double): Int {
        val index = (percent / 100.0 * times.size).roundToInt() - 1
        return times.sorted()[index]
    }

    suspend fun trackEvent(duration: Int) {
        synchronized(lock) { durations.add(duration) }
        withContext(mainDispatcher) {
            latencyTracker.logAction(
                LatencyTracker.ACTION_NOTIFICATION_BIG_PICTURE_LOADED,
                duration
            )
        }
    }
}

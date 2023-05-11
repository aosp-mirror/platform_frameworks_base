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

package com.android.systemui.dump

import android.content.Context
import android.icu.text.SimpleDateFormat
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.util.io.Files
import com.android.systemui.util.time.SystemClock
import java.io.IOException
import java.io.PrintWriter
import java.io.UncheckedIOException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Dumps all [LogBuffer]s to a file
 *
 * Intended for emergencies, i.e. we're about to crash. This file can then be read at a later date
 * (usually in a bug report).
 */
@SysUISingleton
class LogBufferEulogizer(
    private val dumpManager: DumpManager,
    private val systemClock: SystemClock,
    private val files: Files,
    private val logPath: Path,
    private val minWriteGap: Long,
    private val maxLogAgeToDump: Long
) {
    @Inject constructor(
        context: Context,
        dumpManager: DumpManager,
        systemClock: SystemClock,
        files: Files
    ) : this(
        dumpManager,
        systemClock,
        files,
        Paths.get(context.filesDir.toPath().toString(), "log_buffers.txt"),
        MIN_WRITE_GAP,
        MAX_AGE_TO_DUMP
    )

    /**
     * Dumps all active log buffers to a file
     *
     * The file will be prefaced by the [reason], which will then be returned (presumably so it can
     * be thrown).
     */
    fun <T : Exception> record(reason: T): T {
        val start = systemClock.uptimeMillis()
        var duration = 0L

        Log.i(TAG, "Performing emergency dump of log buffers")

        val millisSinceLastWrite = getMillisSinceLastWrite(logPath)
        if (millisSinceLastWrite < minWriteGap) {
            Log.w(TAG, "Cannot dump logs, last write was only $millisSinceLastWrite ms ago")
            return reason
        }

        try {
            val writer = files.newBufferedWriter(logPath, CREATE, TRUNCATE_EXISTING)
            writer.use { out ->
                val pw = PrintWriter(out)

                pw.println(DATE_FORMAT.format(systemClock.currentTimeMillis()))
                pw.println()
                pw.println("Dump triggered by exception:")
                reason.printStackTrace(pw)
                dumpManager.dumpBuffers(pw, 0)
                duration = systemClock.uptimeMillis() - start
                pw.println()
                pw.println("Buffer eulogy took ${duration}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while attempting to dump buffers, bailing", e)
        }

        Log.i(TAG, "Buffer eulogy took ${duration}ms")

        return reason
    }

    /**
     * If a eulogy file is present, writes its contents to [pw].
     */
    fun readEulogyIfPresent(pw: PrintWriter) {
        try {
            val millisSinceLastWrite = getMillisSinceLastWrite(logPath)
            if (millisSinceLastWrite > maxLogAgeToDump) {
                Log.i(TAG, "Not eulogizing buffers; they are " +
                        TimeUnit.HOURS.convert(millisSinceLastWrite, TimeUnit.MILLISECONDS) +
                        " hours old")
                return
            }

            files.lines(logPath).use { s ->
                pw.println()
                pw.println()
                pw.println("=============== BUFFERS FROM MOST RECENT CRASH ===============")
                s.forEach { line ->
                    pw.println(line)
                }
            }
        } catch (e: IOException) {
            // File doesn't exist, okay
        } catch (e: UncheckedIOException) {
            Log.e(TAG, "UncheckedIOException while dumping the core", e)
        }
    }

    private fun getMillisSinceLastWrite(path: Path): Long {
        val stats = try {
            files.readAttributes(path, BasicFileAttributes::class.java)
        } catch (e: IOException) {
            // File doesn't exist
            null
        }
        return systemClock.currentTimeMillis() - (stats?.lastModifiedTime()?.toMillis() ?: 0)
    }
}

private const val TAG = "BufferEulogizer"
private val MIN_WRITE_GAP = TimeUnit.MINUTES.toMillis(5)
private val MAX_AGE_TO_DUMP = TimeUnit.HOURS.toMillis(48)
private val DATE_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
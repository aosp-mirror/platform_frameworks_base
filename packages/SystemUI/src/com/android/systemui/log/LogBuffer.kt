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

package com.android.systemui.log

import android.os.Trace
import android.util.Log
import com.android.systemui.log.dagger.LogModule
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import kotlin.concurrent.thread

/**
 * A simple ring buffer of recyclable log messages
 *
 * The goal of this class is to enable logging that is both extremely chatty and extremely
 * lightweight. If done properly, logging a message will not result in any heap allocations or
 * string generation. Messages are only converted to strings if the log is actually dumped (usually
 * as the result of taking a bug report).
 *
 * You can dump the entire buffer at any time by running:
 *
 * ```
 * $ adb shell dumpsys activity service com.android.systemui/.SystemUIService <bufferName>
 * ```
 *
 * ...where `bufferName` is the (case-sensitive) [name] passed to the constructor.
 *
 * By default, only messages of WARN level or higher are echoed to logcat, but this can be adjusted
 * locally (usually for debugging purposes).
 *
 * To enable logcat echoing for an entire buffer:
 *
 * ```
 * $ adb shell settings put global systemui/buffer/<bufferName> <level>
 * ```
 *
 * To enable logcat echoing for a specific tag:
 *
 * ```
 * $ adb shell settings put global systemui/tag/<tag> <level>
 * ```
 *
 * In either case, `level` can be any of `verbose`, `debug`, `info`, `warn`, `error`, `assert`, or
 * the first letter of any of the previous.
 *
 * Buffers are provided by [LogModule]. Instances should be created using a [LogBufferFactory].
 *
 * @param name The name of this buffer
 * @param maxLogs The maximum number of messages to keep in memory at any one time, including the
 * unused pool. Must be >= [poolSize].
 * @param poolSize The maximum amount that the size of the buffer is allowed to flex in response to
 * sequential calls to [document] that aren't immediately followed by a matching call to [push].
 */
class LogBuffer @JvmOverloads constructor(
    private val name: String,
    private val maxLogs: Int,
    private val poolSize: Int,
    private val logcatEchoTracker: LogcatEchoTracker,
    private val systrace: Boolean = true
) {
    init {
        if (maxLogs < poolSize) {
            throw IllegalArgumentException("maxLogs must be greater than or equal to poolSize, " +
                    "but maxLogs=$maxLogs < $poolSize=poolSize")
        }
    }

    private val buffer: ArrayDeque<LogMessageImpl> = ArrayDeque()
    private val echoMessageQueue: BlockingQueue<LogMessageImpl> = ArrayBlockingQueue(poolSize)

    init {
        thread(start = true, priority = Thread.NORM_PRIORITY) {
            try {
                while (true) {
                    echoToDesiredEndpoints(echoMessageQueue.take())
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    var frozen = false
        private set

    /**
     * Logs a message to the log buffer
     *
     * May also log the message to logcat if echoing is enabled for this buffer or tag.
     *
     * The actual string of the log message is not constructed until it is needed. To accomplish
     * this, logging a message is a two-step process. First, a fresh instance  of [LogMessage] is
     * obtained and is passed to the [initializer]. The initializer stores any relevant data on the
     * message's fields. The message is then inserted into the buffer where it waits until it is
     * either pushed out by newer messages or it needs to printed. If and when this latter moment
     * occurs, the [printer] function is called on the message. It reads whatever data the
     * initializer stored and converts it to a human-readable log message.
     *
     * @param tag A string of at most 23 characters, used for grouping logs into categories or
     * subjects. If this message is echoed to logcat, this will be the tag that is used.
     * @param level Which level to log the message at, both to the buffer and to logcat if it's
     * echoed. In general, a module should split most of its logs into either INFO or DEBUG level.
     * INFO level should be reserved for information that other parts of the system might care
     * about, leaving the specifics of code's day-to-day operations to DEBUG.
     * @param initializer A function that will be called immediately to store relevant data on the
     * log message. The value of `this` will be the LogMessage to be initialized.
     * @param printer A function that will be called if and when the message needs to be dumped to
     * logcat or a bug report. It should read the data stored by the initializer and convert it to
     * a human-readable string. The value of `this` will be the LogMessage to be printed.
     * **IMPORTANT:** The printer should ONLY ever reference fields on the LogMessage and NEVER any
     * variables in its enclosing scope. Otherwise, the runtime will need to allocate a new instance
     * of the printer for each call, thwarting our attempts at avoiding any sort of allocation.
     */
    inline fun log(
        tag: String,
        level: LogLevel,
        initializer: LogMessage.() -> Unit,
        noinline printer: LogMessage.() -> String
    ) {
        if (!frozen) {
            val message = obtain(tag, level, printer)
            initializer(message)
            push(message)
        }
    }

    /**
     * Same as [log], but doesn't push the message to the buffer. Useful if you need to supply a
     * "reason" for doing something (the thing you supply the reason to will presumably call [push]
     * on that message at some point).
     */
    inline fun document(
        tag: String,
        level: LogLevel,
        initializer: LogMessage.() -> Unit,
        noinline printer: LogMessage.() -> String
    ): LogMessage {
        val message = obtain(tag, level, printer)
        initializer(message)
        return message
    }

    /**
     * Obtains an instance of [LogMessageImpl], usually from the object pool. If the pool has been
     * exhausted, creates a new instance.
     *
     * In general, you should call [log] or [document] instead of this method.
     */
    @Synchronized
    fun obtain(
        tag: String,
        level: LogLevel,
        printer: (LogMessage) -> String
    ): LogMessageImpl {
        val message = when {
            frozen -> LogMessageImpl.create()
            buffer.size > maxLogs - poolSize -> buffer.removeFirst()
            else -> LogMessageImpl.create()
        }
        message.reset(tag, level, System.currentTimeMillis(), printer)
        return message
    }

    /**
     * Pushes a message into buffer, possibly evicting an older message if the buffer is full.
     */
    @Synchronized
    fun push(message: LogMessage) {
        if (frozen) {
            return
        }
        if (buffer.size == maxLogs) {
            Log.e(TAG, "LogBuffer $name has exceeded its pool size")
            buffer.removeFirst()
        }
        buffer.add(message as LogMessageImpl)
        // Log in the background thread only if it has capacity to avoid blocking this thread
        if (echoMessageQueue.remainingCapacity() > 0) {
            echoMessageQueue.put(message)
        } else {
            echoToDesiredEndpoints(message)
        }
    }

    /** Sends message to echo after determining whether to use Logcat and/or systrace. */
    private fun echoToDesiredEndpoints(message: LogMessageImpl) {
        val includeInLogcat = logcatEchoTracker.isBufferLoggable(name, message.level) ||
                logcatEchoTracker.isTagLoggable(message.tag, message.level)
        echo(message, toLogcat = includeInLogcat, toSystrace = systrace)
    }

    /** Converts the entire buffer to a newline-delimited string */
    @Synchronized
    fun dump(pw: PrintWriter, tailLength: Int) {
        val start = if (tailLength <= 0) { 0 } else { buffer.size - tailLength }

        for ((i, message) in buffer.withIndex()) {
            if (i >= start) {
                dumpMessage(message, pw)
            }
        }
    }

    /**
     * "Freezes" the contents of the buffer, making them immutable until [unfreeze] is called.
     * Calls to [log], [document], [obtain], and [push] will not affect the buffer and will return
     * dummy values if necessary.
     */
    @Synchronized
    fun freeze() {
        if (!frozen) {
            log(TAG, LogLevel.DEBUG, { str1 = name }, { "$str1 frozen" })
            frozen = true
        }
    }

    /**
     * Undoes the effects of calling [freeze].
     */
    @Synchronized
    fun unfreeze() {
        if (frozen) {
            log(TAG, LogLevel.DEBUG, { str1 = name }, { "$str1 unfrozen" })
            frozen = false
        }
    }

    private fun dumpMessage(message: LogMessage, pw: PrintWriter) {
        pw.print(DATE_FORMAT.format(message.timestamp))
        pw.print(" ")
        pw.print(message.level.shortString)
        pw.print(" ")
        pw.print(message.tag)
        pw.print(": ")
        pw.println(message.printer(message))
    }

    private fun echo(message: LogMessage, toLogcat: Boolean, toSystrace: Boolean) {
        if (toLogcat || toSystrace) {
            val strMessage = message.printer(message)
            if (toSystrace) {
                echoToSystrace(message, strMessage)
            }
            if (toLogcat) {
                echoToLogcat(message, strMessage)
            }
        }
    }

    private fun echoToSystrace(message: LogMessage, strMessage: String) {
        Trace.instantForTrack(Trace.TRACE_TAG_APP, "UI Events",
            "$name - ${message.level.shortString} ${message.tag}: $strMessage")
    }

    private fun echoToLogcat(message: LogMessage, strMessage: String) {
        when (message.level) {
            LogLevel.VERBOSE -> Log.v(message.tag, strMessage)
            LogLevel.DEBUG -> Log.d(message.tag, strMessage)
            LogLevel.INFO -> Log.i(message.tag, strMessage)
            LogLevel.WARNING -> Log.w(message.tag, strMessage)
            LogLevel.ERROR -> Log.e(message.tag, strMessage)
            LogLevel.WTF -> Log.wtf(message.tag, strMessage)
        }
    }
}

private const val TAG = "LogBuffer"
private val DATE_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

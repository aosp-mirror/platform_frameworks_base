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
package com.android.hoststubgen

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.Writer

val log: HostStubGenLogger = HostStubGenLogger().setConsoleLogLevel(LogLevel.Info)

/** Logging level */
enum class LogLevel {
    None,
    Error,
    Warn,
    Info,
    Verbose,
    Debug,
}

/**
 * Simple logging class.
 *
 * By default, it has no printers set. Use [setConsoleLogLevel] or [addFilePrinter] to actually
 * write log.
 */
class HostStubGenLogger {
    private var indentLevel: Int = 0
        get() = field
        set(value) {
            field = value
            indent = "  ".repeat(value)
        }
    private var indent: String = ""

    private val printers: MutableList<LogPrinter> = mutableListOf()

    private var consolePrinter: LogPrinter? = null

    private var maxLogLevel = LogLevel.None

    private fun updateMaxLogLevel() {
        maxLogLevel = LogLevel.None

        printers.forEach {
            if (maxLogLevel < it.logLevel) {
                maxLogLevel = it.logLevel
            }
        }
    }

    private fun addPrinter(printer: LogPrinter) {
        printers.add(printer)
        updateMaxLogLevel()
    }

    private fun removePrinter(printer: LogPrinter) {
        printers.remove(printer)
        updateMaxLogLevel()
    }

    fun setConsoleLogLevel(level: LogLevel): HostStubGenLogger {
        // If there's already a console log printer set, remove it, and then add a new one
        consolePrinter?.let {
            removePrinter(it)
        }
        val cp = StreamPrinter(level, PrintWriter(System.out))
        addPrinter(cp)
        consolePrinter = cp

        return this
    }

    fun addFilePrinter(level: LogLevel, logFilename: String): HostStubGenLogger {
        addPrinter(StreamPrinter(level, PrintWriter(BufferedOutputStream(
            FileOutputStream(logFilename)))))

        log.i("Log file set: $logFilename for $level")

        return this
    }

    /** Flush all the printers */
    fun flush() {
        printers.forEach { it.flush() }
    }

    fun indent() {
        indentLevel++
    }

    fun unindent() {
        if (indentLevel <= 0) {
            throw IllegalStateException("Unbalanced unindent() call.")
        }
        indentLevel--
    }

    inline fun <T> withIndent(block: () -> T): T {
        try {
            indent()
            return block()
        } finally {
            unindent()
        }
    }

    fun isEnabled(level: LogLevel): Boolean {
        return level.ordinal <= maxLogLevel.ordinal
    }

    fun println(level: LogLevel, message: String) {
        if (message.isEmpty()) {
            return // Don't print an empty message.
        }
        printers.forEach {
            if (it.logLevel.ordinal >= level.ordinal) {
                it.println(level, indent, message)
            }
        }
    }

    fun println(level: LogLevel, format: String, vararg args: Any?) {
        if (isEnabled(level)) {
            println(level, String.format(format, *args))
        }
    }

    /** Log an error. */
    fun e(message: String) {
        println(LogLevel.Error, message)
    }

    /** Log an error. */
    fun e(format: String, vararg args: Any?) {
        println(LogLevel.Error, format, *args)
    }

    /** Log a warning. */
    fun w(message: String) {
        println(LogLevel.Warn, message)
    }

    /** Log a warning. */
    fun w(format: String, vararg args: Any?) {
        println(LogLevel.Warn, format, *args)
    }

    /** Log an info message. */
    fun i(message: String) {
        println(LogLevel.Info, message)
    }

    /** Log an info message. */
    fun i(format: String, vararg args: Any?) {
        println(LogLevel.Info, format, *args)
    }

    /** Log a verbose message. */
    fun v(message: String) {
        println(LogLevel.Verbose, message)
    }

    /** Log a verbose message. */
    fun v(format: String, vararg args: Any?) {
        println(LogLevel.Verbose, format, *args)
    }

    /** Log a debug message. */
    fun d(message: String) {
        println(LogLevel.Debug, message)
    }

    /** Log a debug message. */
    fun d(format: String, vararg args: Any?) {
        println(LogLevel.Debug, format, *args)
    }

    inline fun <T> logTime(level: LogLevel, message: String, block: () -> T): Double {
        var ret: Double = -1.0
        val start = System.currentTimeMillis()
        try {
            block()
        } finally {
            val end = System.currentTimeMillis()
            ret = (end - start) / 1000.0
            if (isEnabled(level)) {
                println(level,
                    String.format("%s: took %.1f second(s).", message, (end - start) / 1000.0))
            }
        }
        return ret
    }

    /** Do an "i" log with how long it took. */
    inline fun <T> iTime(message: String, block: () -> T): Double {
        return logTime(LogLevel.Info, message, block)
    }

    /** Do a "v" log with how long it took. */
    inline fun <T> vTime(message: String, block: () -> T): Double {
        return logTime(LogLevel.Verbose, message, block)
    }

    /** Do a "d" log with how long it took. */
    inline fun <T> dTime(message: String, block: () -> T): Double {
        return logTime(LogLevel.Debug, message, block)
    }

    /**
     * Similar to the other "xTime" methods, but the message is not supposed to be printed.
     * It's only used to measure the duration with the same interface as other log methods.
     */
    inline fun <T> nTime(block: () -> T): Double {
        return logTime(LogLevel.Debug, "", block)
    }

    inline fun forVerbose(block: () -> Unit) {
        if (isEnabled(LogLevel.Verbose)) {
            block()
        }
    }

    inline fun forDebug(block: () -> Unit) {
        if (isEnabled(LogLevel.Debug)) {
            block()
        }
    }

    /** Return a Writer for a given log level. */
    fun getWriter(level: LogLevel): Writer {
        return MultiplexingWriter(level)
    }

    private inner class MultiplexingWriter(val level: LogLevel) : Writer() {
        private inline fun forPrinters(callback: (LogPrinter) -> Unit) {
            printers.forEach {
                if (it.logLevel.ordinal >= level.ordinal) {
                    callback(it)
                }
            }
        }

        override fun close() {
            flush()
        }

        override fun flush() {
            forPrinters {
                it.flush()
            }
        }

        override fun write(cbuf: CharArray, off: Int, len: Int) {
            // TODO Apply indent
            forPrinters {
                it.write(cbuf, off, len)
            }
        }
    }

    /**
     * Handle log-related command line arguments.
     */
    fun maybeHandleCommandLineArg(currentArg: String, nextArgProvider: () -> String): Boolean {
        when (currentArg) {
            "-v", "--verbose" -> setConsoleLogLevel(LogLevel.Verbose)
            "-d", "--debug" -> setConsoleLogLevel(LogLevel.Debug)
            "-q", "--quiet" -> setConsoleLogLevel(LogLevel.None)
            "--verbose-log" -> addFilePrinter(LogLevel.Verbose, nextArgProvider())
            "--debug-log" -> addFilePrinter(LogLevel.Debug, nextArgProvider())
            else -> return false
        }
        return true
    }
}

private interface LogPrinter {
    val logLevel: LogLevel

    fun println(logLevel: LogLevel, indent: String, message: String)

    // TODO: This should be removed once MultiplexingWriter starts applying indent, at which point
    // println() should be used instead.
    fun write(cbuf: CharArray, off: Int, len: Int)

    fun flush()
}

private class StreamPrinter(
    override val logLevel: LogLevel,
    val out: PrintWriter,
) : LogPrinter {
    override fun println(logLevel: LogLevel, indent: String, message: String) {
        out.print(indent)
        out.println(message)
    }

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        out.write(cbuf, off, len)
    }

    override fun flush() {
        out.flush()
    }
}

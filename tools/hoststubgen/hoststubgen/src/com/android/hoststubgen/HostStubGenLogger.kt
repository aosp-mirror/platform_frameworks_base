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

import java.io.OutputStream
import java.io.PrintStream

val log: HostStubGenLogger = HostStubGenLogger()

/** Logging level */
enum class LogLevel {
    None,
    Error,
    Warn,
    Info,
    Verbose,
    Debug,
}

/** Simple logging class. */
class HostStubGenLogger(
        private var out: PrintStream = System.out!!,
        var level: LogLevel = LogLevel.Info,
) {
    companion object {
        private val sNullPrintStream: PrintStream = PrintStream(OutputStream.nullOutputStream())
    }

    private var indentLevel: Int = 0
        get() = field
        set(value) {
            field = value
            indent = "  ".repeat(value)
        }
    private var indent: String = ""

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
        return level.ordinal <= this.level.ordinal
    }

    private fun println(message: String) {
        out.print(indent)
        out.println(message)
    }

    /** Log an error. */
    fun e(message: String) {
        if (level.ordinal < LogLevel.Error.ordinal) {
            return
        }
        println(message)
    }

    /** Log an error. */
    fun e(format: String, vararg args: Any?) {
        if (level.ordinal < LogLevel.Error.ordinal) {
            return
        }
        e(String.format(format, *args))
    }

    /** Log a warning. */
    fun w(message: String) {
        if (level.ordinal < LogLevel.Warn.ordinal) {
            return
        }
        println(message)
    }

    /** Log a warning. */
    fun w(format: String, vararg args: Any?) {
        if (level.ordinal < LogLevel.Warn.ordinal) {
            return
        }
        w(String.format(format, *args))
    }

    /** Log an info message. */
    fun i(message: String) {
        if (level.ordinal < LogLevel.Info.ordinal) {
            return
        }
        println(message)
    }

    /** Log a debug message. */
    fun i(format: String, vararg args: Any?) {
        if (level.ordinal < LogLevel.Warn.ordinal) {
            return
        }
        i(String.format(format, *args))
    }

    /** Log a verbose message. */
    fun v(message: String) {
        if (level.ordinal < LogLevel.Verbose.ordinal) {
            return
        }
        println(message)
    }

    /** Log a verbose message. */
    fun v(format: String, vararg args: Any?) {
        if (level.ordinal < LogLevel.Verbose.ordinal) {
            return
        }
        v(String.format(format, *args))
    }

    /** Log a debug message. */
    fun d(message: String) {
        if (level.ordinal < LogLevel.Debug.ordinal) {
            return
        }
        println(message)
    }

    /** Log a debug message. */
    fun d(format: String, vararg args: Any?) {
        if (level.ordinal < LogLevel.Warn.ordinal) {
            return
        }
        d(String.format(format, *args))
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

    /** Return a stream for error. */
    fun getErrorPrintStream(): PrintStream {
        if (level.ordinal < LogLevel.Error.ordinal) {
            return sNullPrintStream
        }

        // TODO Apply indent
        return PrintStream(out)
    }

    /** Return a stream for verbose messages. */
    fun getVerbosePrintStream(): PrintStream {
        if (level.ordinal < LogLevel.Verbose.ordinal) {
            return sNullPrintStream
        }
        // TODO Apply indent
        return PrintStream(out)
    }

    /** Return a stream for debug messages. */
    fun getInfoPrintStream(): PrintStream {
        if (level.ordinal < LogLevel.Info.ordinal) {
            return sNullPrintStream
        }
        // TODO Apply indent
        return PrintStream(out)
    }
}
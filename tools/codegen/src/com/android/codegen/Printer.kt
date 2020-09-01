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


package com.android.codegen

/**
 * Mixin for syntactic sugar around indent-aware printing into [stringBuilder]
 */
interface Printer<SELF: Printer<SELF>> {

    val stringBuilder: StringBuilder

    var currentIndent: String

    fun pushIndent() {
        currentIndent += INDENT_SINGLE
    }

    fun popIndent() {
        currentIndent = if (currentIndent.length >= INDENT_SINGLE.length) {
            currentIndent.substring(0, currentIndent.length - INDENT_SINGLE.length)
        } else {
            ""
        }
    }

    fun backspace() = stringBuilder.setLength(stringBuilder.length - 1)
    val lastChar get() = stringBuilder[stringBuilder.length - 1]

    private fun appendRaw(s: String) {
        stringBuilder.append(s)
    }

    fun append(s: String) {
        if (s.isBlank() && s != "\n") {
            appendRaw(s)
        } else {
            appendRaw(s.lines().map { line ->
                if (line.startsWith(" *")) line else line.trimStart()
            }.joinToString("\n$currentIndent"))
        }
    }

    fun appendSameLine(s: String) {
        while (lastChar.isWhitespace() || lastChar.isNewline()) {
            backspace()
        }
        appendRaw(s)
    }

    fun rmEmptyLine() {
        while (lastChar.isWhitespaceNonNewline()) backspace()
        if (lastChar.isNewline()) backspace()
    }

    /**
     * Syntactic sugar for:
     * ```
     * +"code()";
     * ```
     * to append the given string plus a newline
     */
    operator fun String.unaryPlus() = append("$this\n")

    /**
     * Syntactic sugar for:
     * ```
     * !"code()";
     * ```
     * to append the given string without a newline
     */
    operator fun String.not() = append(this)

    /**
     * Syntactic sugar for:
     * ```
     * ... {
     *     ...
     * }+";"
     * ```
     * to append a ';' on same line after a block, and a newline afterwards
     */
    operator fun Unit.plus(s: String) {
        appendSameLine(s)
        +""
    }

    /**
     * A multi-purpose syntactic sugar for appending the given string plus anything generated in
     * the given [block], the latter with the appropriate deeper indent,
     * and resetting the indent back to original at the end
     *
     * Usage examples:
     *
     * ```
     * "if (...)" {
     *     ...
     * }
     * ```
     * to append a corresponding if block appropriate indentation
     *
     * ```
     * "void foo(...)" {
     *      ...
     * }
     * ```
     * similar to the previous one, plus an extra empty line after the function body
     *
     * ```
     * "void foo(" {
     *      <args code>
     * }
     * ```
     * to use proper indentation for args code and close the bracket on same line at end
     *
     * ```
     * "..." {
     *     ...
     * }
     * to use the correct indentation for inner code, resetting it at the end
     */
    operator fun String.invoke(block: SELF.() -> Unit) {
        if (this == " {") {
            appendSameLine(this)
        } else {
            append(this)
        }
        when {
            endsWith("(") -> {
                indentedBy(2, block)
                appendSameLine(")")
            }
            endsWith("{") || endsWith(")") -> {
                if (!endsWith("{")) appendSameLine(" {")
                indentedBy(1, block)
                +"}"
                if ((endsWith(") {") || endsWith(")") || this == " {")
                        && !startsWith("synchronized")
                        && !startsWith("switch")
                        && !startsWith("if ")
                        && !contains(" else ")
                        && !contains("new ")
                        && !contains("return ")) {
                    +"" // extra line after function definitions
                }
            }
            else -> indentedBy(2, block)
        }
    }

    fun indentedBy(level: Int, block: SELF.() -> Unit) {
        append("\n")
        level times {
            append(INDENT_SINGLE)
            pushIndent()
        }
        (this as SELF).block()
        level times { popIndent() }
        rmEmptyLine()
        +""
    }

    fun Iterable<FieldInfo>.forEachTrimmingTrailingComma(b: FieldInfo.() -> Unit) {
        forEachApply {
            b()
            if (isLast) {
                while (lastChar == ' ' || lastChar == '\n') backspace()
                if (lastChar == ',') backspace()
            }
        }
    }
}
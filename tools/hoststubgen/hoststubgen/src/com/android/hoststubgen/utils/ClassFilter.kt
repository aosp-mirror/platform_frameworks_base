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
package com.android.hoststubgen.utils

import com.android.hoststubgen.ParseException
import com.android.hoststubgen.asm.toHumanReadableClassName
import com.android.hoststubgen.asm.toJvmClassName
import com.android.hoststubgen.normalizeTextLine
import java.io.File

/**
 * General purpose filter for class names.
 */
class ClassFilter private constructor (
        val defaultResult: Boolean,
) {
    private data class FilterElement(
            val allowed: Boolean,
            val internalName: String,
            val isPrefix: Boolean,
    ) {
        fun matches(classInternalName: String): Boolean {
            if (isPrefix) {
                return classInternalName.startsWith(internalName)
            } else {
                return classInternalName == internalName
            }
        }
    }

    private val elements: MutableList<FilterElement> = mutableListOf()

    private val cache: MutableMap<String, Boolean> = mutableMapOf()

    /**
     * Takes an internal class name (e.g. "com/android/hoststubgen/ClassName") and returns if
     * matches the filter or not.
     */
    fun matches(classInternalName: String): Boolean {
        cache[classInternalName]?.let {
            return it
        }

        var result = defaultResult
        run outer@{
            elements.forEach { e ->
                if (e.matches(classInternalName)) {
                    result = e.allowed
                    return@outer // break equivalent.
                }
            }
        }
        cache[classInternalName] = result

        return result
    }

    fun getCacheSizeForTest(): Int {
        return cache.size
    }

    companion object {
        /**
         * Return a filter that alawys returns true or false.
         */
        fun newNullFilter(defaultResult: Boolean): ClassFilter {
            return ClassFilter(defaultResult)
        }

        /** Build a filter from a file. */
        fun loadFromFile(filename: String, defaultResult: Boolean): ClassFilter {
            return buildFromString(File(filename).readText(), defaultResult, filename)
        }

        /** Build a filter from a string (for unit tests). */
        fun buildFromString(
                filterString: String,
                defaultResult: Boolean,
                filenameForErrorMessage: String
        ): ClassFilter {
            val ret = ClassFilter(defaultResult)

            var lineNo = 0
            filterString.split('\n').forEach { s ->
                lineNo++

                var line = normalizeTextLine(s)

                if (line.isEmpty()) {
                    return@forEach // skip empty lines.
                }

                line = line.toHumanReadableClassName() // Convert all the slashes to periods.

                var allow = true
                if (line.startsWith("!")) {
                    allow = false
                    line = line.substring(1).trimStart()
                }

                // Special case -- matches any class names.
                if (line == "*") {
                    ret.elements.add(FilterElement(allow, "", true))
                    return@forEach
                }

                // Handle wildcard -- e.g. "package.name.*"
                if (line.endsWith(".*")) {
                    ret.elements.add(FilterElement(
                            allow, line.substring(0, line.length - 2).toJvmClassName(), true))
                    return@forEach
                }

                // Any other uses of "*" would be an error.
                if (line.contains('*')) {
                    throw ParseException(
                            "Wildcard (*) can only show up as the last element",
                            filenameForErrorMessage,
                            lineNo
                    )
                }
                ret.elements.add(FilterElement(allow, line.toJvmClassName(), false))
            }

            return ret
        }
    }
}
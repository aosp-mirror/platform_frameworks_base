/*
 * Copyright (C) 2024 The Android Open Source Project
 *
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
@file:JvmName("DalComponentParser")

package com.android.statementservice.parser

import android.os.PatternMatcher.PATTERN_ADVANCED_GLOB
import android.os.PatternMatcher.PATTERN_LITERAL
import android.os.PatternMatcher.PATTERN_PREFIX
import android.os.PatternMatcher.PATTERN_SIMPLE_GLOB

/**
 * Parses a DAL component matching expression to Android's {@link android.os.PatternMatcher} type
 * and pattern. Matching expressions support the following wildcards:
 *
 *  1) An asterisk (*) matches zero to as many characters as possible
 *  2) A question mark (?) matches any single character.
 *
 * Matching one to many characters can be done with a question mark followed by an asterisk (?+).
 *
 * @param expression A matching expression string from a DAL relation extension component used for
 *                   matching a URI part. This must be a non-empty string and all characters in the
 *                   string should be decoded.
 *
 * @return Returns a Pair containing a {@link android.os.PatternMatcher} type and pattern.
 */
fun parseMatchingExpression(expression: String): Pair<Int, String> {
    if (expression.isNullOrEmpty()) {
        throw IllegalArgumentException("Matching expressions cannot be an empty string")
    }
    var count = 0
    var isAdvanced = expression.contains("?*")
    val pattern = buildString {
        for (char in expression) {
            when (char) {
                '*' -> {
                    if (this.endsWith('.') && !this.endsWith("\\.")) {
                        append('+')
                    } else {
                        count += 1
                        append(".*")
                    }
                }
                '?' -> {
                    count += 1
                    append('.')
                }
                '.' -> {
                    append("\\.")
                }
                '[', ']', '{', '}' -> {
                    if (isAdvanced) {
                        append('\\')
                    }
                    append(char)
                }
                else -> append(char)
            }
        }
    }
    if (count == 0) {
        return Pair(PATTERN_LITERAL, pattern)
    }
    if (count == 1 && pattern.endsWith(".*")) {
        return Pair(PATTERN_PREFIX, pattern.dropLast(2))
    }
    if (isAdvanced) {
        return Pair(PATTERN_ADVANCED_GLOB, pattern)
    }
    return Pair(PATTERN_SIMPLE_GLOB, pattern)
}
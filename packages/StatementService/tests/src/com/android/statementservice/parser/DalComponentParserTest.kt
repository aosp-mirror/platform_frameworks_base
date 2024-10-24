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
package com.android.statementservice.parser

import android.os.PatternMatcher.PATTERN_ADVANCED_GLOB
import android.os.PatternMatcher.PATTERN_LITERAL
import android.os.PatternMatcher.PATTERN_PREFIX
import android.os.PatternMatcher.PATTERN_SIMPLE_GLOB
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DalComponentParserTest {

    @Test
    fun parseExpressions() {
        validateParsedExpression("foobar", PATTERN_LITERAL, "foobar")
        validateParsedExpression("foo.bar", PATTERN_LITERAL, "foo\\.bar")
        validateParsedExpression("foo*", PATTERN_PREFIX, "foo")
        validateParsedExpression("*bar", PATTERN_SIMPLE_GLOB, ".*bar")
        validateParsedExpression("foo*bar", PATTERN_SIMPLE_GLOB, "foo.*bar")
        validateParsedExpression("foo.*bar", PATTERN_SIMPLE_GLOB, "foo\\..*bar")
        validateParsedExpression("*foo*bar", PATTERN_SIMPLE_GLOB, ".*foo.*bar")
        validateParsedExpression("foo?bar", PATTERN_SIMPLE_GLOB, "foo.bar")
        validateParsedExpression("foo.?bar", PATTERN_SIMPLE_GLOB, "foo\\..bar")
        validateParsedExpression("?bar", PATTERN_SIMPLE_GLOB, ".bar")
        validateParsedExpression("foo?", PATTERN_SIMPLE_GLOB, "foo.")
        validateParsedExpression("fo?b*r", PATTERN_SIMPLE_GLOB, "fo.b.*r")
        validateParsedExpression("?*bar", PATTERN_ADVANCED_GLOB, ".+bar")
        validateParsedExpression("foo?*bar", PATTERN_ADVANCED_GLOB, "foo.+bar")
        validateParsedExpression("foo?*bar*", PATTERN_ADVANCED_GLOB, "foo.+bar.*")
        validateParsedExpression("foo*?bar", PATTERN_SIMPLE_GLOB, "foo.*.bar")

        // set matches are not supported in DAL
        validateParsedExpression("foo[a-z]", PATTERN_LITERAL, "foo[a-z]")
        validateParsedExpression("foo[a-z]+", PATTERN_LITERAL, "foo[a-z]+")
        validateParsedExpression("foo[a-z]*", PATTERN_PREFIX, "foo[a-z]")
        validateParsedExpression("[a-z]*bar", PATTERN_SIMPLE_GLOB, "[a-z].*bar")
        validateParsedExpression("foo[a-z]?bar", PATTERN_SIMPLE_GLOB, "foo[a-z].bar")
        validateParsedExpression("foo[a-z]?*bar", PATTERN_ADVANCED_GLOB, "foo\\[a-z\\].+bar")

        // range matches are not supported in DAL
        validateParsedExpression("fo{2}", PATTERN_LITERAL, "fo{2}")
        validateParsedExpression("fo{2}+", PATTERN_LITERAL, "fo{2}+")
        validateParsedExpression("fo{2}*", PATTERN_PREFIX, "fo{2}")
        validateParsedExpression("fo{2}*bar", PATTERN_SIMPLE_GLOB, "fo{2}.*bar")
        validateParsedExpression("fo{2}?*", PATTERN_ADVANCED_GLOB, "fo\\{2\\}.+")
        validateParsedExpression("foo{2}?*bar", PATTERN_ADVANCED_GLOB, "foo\\{2\\}.+bar")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseEmptyExpression() {
        parseMatchingExpression("")
    }

    private fun validateParsedExpression(given: String, expectedType: Int, expectedFilter: String) {
        val (type, filter) = parseMatchingExpression(given)
        assertThat(filter).isEqualTo(expectedFilter)
        assertThat(type).isEqualTo(expectedType)
        assertThat(filter).isEqualTo(expectedFilter)
    }
}
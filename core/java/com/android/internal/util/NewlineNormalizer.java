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

package com.android.internal.util;


import java.util.regex.Pattern;

/**
 * Utility class that replaces consecutive empty lines with single new line.
 * @hide
 */
public class NewlineNormalizer {

    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\\v(\\s*\\v)?");

    // Private constructor to prevent instantiation
    private NewlineNormalizer() {}

    /**
     * Replaces consecutive newlines with a single newline in the input text.
     */
    public static String normalizeNewlines(String text) {
        return MULTIPLE_NEWLINES.matcher(text).replaceAll("\n");
    }
}

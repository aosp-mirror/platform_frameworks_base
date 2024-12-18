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


import android.annotation.NonNull;
import android.os.Trace;

import java.util.regex.Pattern;

/**
 * Utility class that normalizes BigText style Notification content.
 * @hide
 */
public class NotificationBigTextNormalizer {

    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\\v(\\s*\\v)?");
    private static final Pattern HORIZONTAL_WHITESPACES = Pattern.compile("\\h+");

    // Private constructor to prevent instantiation
    private NotificationBigTextNormalizer() {}

    /**
     * Normalizes the given text by collapsing consecutive new lines into single one and cleaning
     * up each line by removing zero-width characters, invisible formatting characters, and
     * collapsing consecutive whitespace into single space.
     */
    @NonNull
    public static String normalizeBigText(@NonNull String text) {
        try {
            Trace.beginSection("NotifBigTextNormalizer#normalizeBigText");
            text = MULTIPLE_NEWLINES.matcher(text).replaceAll("\n");
            text = HORIZONTAL_WHITESPACES.matcher(text).replaceAll(" ");
            text = normalizeLines(text);
            return text;
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Normalizes lines in a text by removing zero-width characters, invisible formatting
     * characters, and collapsing consecutive whitespace into single space.
     *
     * <p>
     * This method processes the input text line by line. It eliminates zero-width
     * characters (U+200B to U+200D, U+FEFF, U+034F), invisible formatting
     * characters (U+2060 to U+2065, U+206A to U+206F, U+FFF9 to U+FFFB),
     * and replaces any sequence of consecutive whitespace characters with a single space.
     * </p>
     *
     * <p>
     * Additionally, the method trims trailing whitespace from each line and removes any
     * resulting empty lines.
     * </p>
     */
    @NonNull
    private static String normalizeLines(@NonNull String text) {
        String[] lines = text.split("\n");
        final StringBuilder textSB = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            final StringBuilder lineSB = new StringBuilder(line.length());
            boolean spaceSeen = false;
            for (int j = 0; j < line.length(); j++) {
                final char character = line.charAt(j);

                // Skip ZERO WIDTH characters
                if ((character >= '\u200B' && character <= '\u200D')
                        || character == '\uFEFF' || character == '\u034F') {
                    continue;
                }
                // Skip INVISIBLE_FORMATTING_CHARACTERS
                if ((character >= '\u2060' && character <= '\u2065')
                        || (character >= '\u206A' && character <= '\u206F')
                        || (character >= '\uFFF9' && character <= '\uFFFB')) {
                    continue;
                }

                if (isSpace(character)) {
                    // eliminate consecutive spaces....
                    if (!spaceSeen) {
                        lineSB.append(" ");
                    }
                    spaceSeen = true;
                } else {
                    spaceSeen = false;
                    lineSB.append(character);
                }
            }
            // trim line.
            final String currentLine = lineSB.toString().trim();

            // don't add empty lines after trim.
            if (currentLine.length() > 0) {
                if (textSB.length() > 0) {
                    textSB.append("\n");
                }
                textSB.append(currentLine);
            }
        }

        return textSB.toString();
    }

    private static boolean isSpace(char ch) {
        return ch != '\n' && Character.isSpaceChar(ch);
    }
}
